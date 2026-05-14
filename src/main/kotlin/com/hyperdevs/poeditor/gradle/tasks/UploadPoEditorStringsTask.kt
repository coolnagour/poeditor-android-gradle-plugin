@file:Suppress("NoUnusedImports", "UnusedImports") // Needed because detekt removes the "assign" import
/*
 * Copyright 2021 HyperDevs
 *
 * Copyright 2020 BQ
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hyperdevs.poeditor.gradle.tasks

import com.hyperdevs.poeditor.gradle.DefaultValues
import com.hyperdevs.poeditor.gradle.PoEditorPluginExtension
import com.hyperdevs.poeditor.gradle.PoEditorStringsUploader
import com.hyperdevs.poeditor.gradle.utils.DEFAULT_PLUGIN_NAME
import com.hyperdevs.poeditor.gradle.utils.POEDITOR_CONFIG_NAME
import com.hyperdevs.poeditor.gradle.utils.getResourceDirectory
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.assign
import javax.inject.Inject

/**
 * Task that:
 * 1. Uploads default language terms to POEditor
 */
abstract class UploadPoEditorStringsTask @Inject constructor() : DefaultTask() {
    /**
     * PoEditor API token.
     *
     * Must be present in order to run the plugin.
     */
    @get:Input
    abstract val apiToken: Property<String>

    /**
     * PoEditor project ID.
     *
     * Must be present in order to run the plugin.
     */
    @get:Input
    abstract val projectId: Property<Int>

    /**
     * Default language of the project, in ISO-2 format.
     *
     * Defaults to 'en' if not defined.
     */
    @get:Optional
    @get:Input
    abstract val defaultLang: Property<String>

    /**
     * Default resources path for the module where the strings should be put in.
     *
     * Defaults to the module with the `com.android.application` plugin.
     */
    @get:Optional
    @get:Input
    abstract val defaultResPath: Property<String>

    /**
     * File name of the string resource files.
     *
     * Defaults to "strings" if not defined.
     */
    @get:Optional
    @get:Input
    abstract val resFileName: Property<String>

    /**
     * Tags to filter downloaded strings with, previously declared in PoEditor.
     *
     * Defaults to an empty list of tags if not present.
     */
    @get:Optional
    @get:Input
    abstract val tags: ListProperty<String>

    /**
     * Map of languages to override their default values folder.
     *
     * Defaults to an empty map of language overrides map.
     */
    @get:Optional
    @get:Input
    abstract val languageValuesOverridePathMap: MapProperty<String, String>

    /**
     * The timeout for uploading the strings to PoEditor.
     *
     * Defaults to 60s.
     */
    @get:Optional
    @get:Input
    abstract val httpTimeout: Property<Long>

    /**
     * Whether to overwrite default language values on PoEditor with the local ones.
     *
     * Defaults to false. Can be enabled from the CLI with `--update-default`.
     */
    @get:Input
    @set:Option(
        option = "update-default",
        description = "Also overwrite default language values on PoEditor with the local ones."
    )
    var updateDefault: Boolean = false

    /**
     * Comma-separated PoEditor language codes whose translations should be overwritten on PoEditor
     * with the local resource values (e.g. `"ar-ae"` or `"ar-ae,fr-fr"`). For each listed code the
     * matching local `values-<modifier>` folder is treated as the source of truth.
     *
     * Typical use: locales whose handling on the PoEditor dashboard is unreliable
     * (e.g. RTL languages, special-character locales). See the README for a list of common codes.
     *
     * Defaults to empty (no overwrite). Can be set from the CLI with
     * `--overwrite-langs ar-ae,fr-fr`.
     */
    @get:Input
    @set:Option(
        option = "overwrite-langs",
        description = "Comma-separated PoEditor language codes whose translations should be overwritten " +
                      "with local values (e.g. --overwrite-langs ar-ae,fr-fr)."
    )
    var overwriteLangs: String = ""

    /**
     * Main task entrypoint.
     */
    @TaskAction
    @Suppress("ThrowsCount")
    fun uploadPoEditorStrings() {
        // Ensure that mandatory parameters are defined
        val apiToken: String
        val projectId: Int

        try {
            apiToken = this.apiToken.get()
            projectId = this.projectId.get()
        } catch (e: Exception) {
            logger.error("Import configuration failed", e)

            throw IllegalArgumentException(
                "You don't have the config properly set-up in your '$POEDITOR_CONFIG_NAME' block " +
                "or you don't have your main '$DEFAULT_PLUGIN_NAME' config properly set-up.\n" +
                "Please review the input parameters of both blocks and try again.")
        }

        val overwriteLangsList = overwriteLangs.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        PoEditorStringsUploader.uploadPoEditorStrings(
            apiToken = apiToken,
            projectId = projectId,
            defaultLang = defaultLang.getOrElse(DefaultValues.DEFAULT_LANG),
            languageCode = defaultLang.getOrElse(DefaultValues.DEFAULT_LANG),
            resDirPath = defaultResPath.getOrElse(getResourceDirectory(project, DefaultValues.MAIN_CONFIG_NAME).absolutePath),
            tags = tags.getOrElse(DefaultValues.TAGS),
            languageValuesOverridePathMap = languageValuesOverridePathMap.getOrElse(DefaultValues.LANGUAGE_VALUES_OVERRIDE_PATH_MAP),
            resFileName = resFileName.getOrElse(DefaultValues.RES_FILE_NAME),
            updateDefault = updateDefault,
            timeout = httpTimeout.getOrElse(DefaultValues.TIMEOUT),
            overwriteLangs = overwriteLangsList
        )
    }

    internal fun configureTask(extension: PoEditorPluginExtension) {
        this.apiToken = extension.apiToken
        this.projectId = extension.projectId
        this.defaultLang = extension.defaultLang
        this.defaultResPath = extension.defaultResPath
        this.resFileName = extension.resFileName
        this.tags = extension.tags
        this.languageValuesOverridePathMap = extension.languageValuesOverridePathMap
        this.httpTimeout = extension.httpTimeout
    }
}
