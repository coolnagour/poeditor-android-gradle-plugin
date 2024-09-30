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

package com.hyperdevs.poeditor.gradle

import com.hyperdevs.poeditor.gradle.adapters.PoEditorDateJsonAdapter
import com.hyperdevs.poeditor.gradle.ktx.asList
import com.hyperdevs.poeditor.gradle.network.PoEditorApiControllerImpl
import com.hyperdevs.poeditor.gradle.network.api.PoEditorApi
import com.hyperdevs.poeditor.gradle.network.api.ProjectLanguage
import com.hyperdevs.poeditor.gradle.network.api.Term
import com.hyperdevs.poeditor.gradle.network.api.UpdatingType
import com.hyperdevs.poeditor.gradle.utils.createValuesModifierFromLangCode
import com.hyperdevs.poeditor.gradle.utils.logger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.FileInputStream
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Main class that uploads android xml string files to PoEditor.
 */
object PoEditorStringsUploader {
    private const val POEDITOR_API_URL = "https://api.poeditor.com/v2/"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .add(Date::class.java, PoEditorDateJsonAdapter())
        .build()

    private lateinit var okHttpClient: OkHttpClient

    private lateinit var retrofit: Retrofit

    private lateinit var poEditorApi: PoEditorApi

    /**
     * Uploads PoEditor strings.
     */
    @Suppress("LongParameterList")
    fun uploadPoEditorStrings(apiToken: String,
                              projectId: Int,
                              defaultLang: String,
                              languageCode: String,
                              resDirPath: String,
                              tags: List<String>,
                              languageValuesOverridePathMap: Map<String, String>,
                              resFileName: String,
                              timeout: Long) {
        try {
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout, TimeUnit.SECONDS)
                .addInterceptor(HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
                    override fun log(message: String) {
                        logger.debug(message)
                    }
                })
                    .setLevel(HttpLoggingInterceptor.Level.BODY))
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(POEDITOR_API_URL.toHttpUrl())
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            poEditorApi = retrofit.create(PoEditorApi::class.java)

            logger.lifecycle("WRITE_TIMEOUT_SECONDS: $timeout")

            val poEditorApiController = PoEditorApiControllerImpl(apiToken, moshi, poEditorApi)

            // Retrieve available languages from PoEditor
            logger.lifecycle("Get project languages xml files... $apiToken")

            // First check if we have passed a default "values" folder for the given language
            var baseValuesDir: File? = languageValuesOverridePathMap?.get(languageCode)?.let { File(it) }

            // If we haven't passed a default base values directory, compose the base values folder
            if (baseValuesDir == null) {
                var valuesFolderName = "values"

                val valuesModifier = createValuesModifierFromLangCode(languageCode)
                if (valuesModifier != defaultLang) valuesFolderName = "$valuesFolderName-$valuesModifier"

                baseValuesDir = File(File(resDirPath), valuesFolderName)
            }

            val mainValuesFile = File(baseValuesDir, "$resFileName.xml")
            if (mainValuesFile.exists()) {
                syncTerms(mainValuesFile, projectId, poEditorApiController, tags)

                // Retrieve translation file URL for the given language and for the "android_strings" type,
                // acknowledging passed tags if present
                logger.lifecycle("Uploading strings file for language code: $languageCode")
                val result = poEditorApiController.uploadProjectLanguage(
                    projectId = projectId,
                    code = languageCode,
                    updating = UpdatingType.TERMS_TRANSLATIONS,
                    file = mainValuesFile,
                    overwrite = true,
                    syncTerms = false,
                    fuzzyTrigger = true,
                    tags = tags
                )
                logger.lifecycle("Uploaded file result : $result")
            }
//            val tabletValuesFile = File("${baseValuesDir.absolutePath}-$TABLET_RES_FOLDER_SUFFIX", "$resFileName.xml")
        } catch (e: Exception) {
            logger.error("An error happened when retrieving strings from project. " +
                         "Please review the plug-in's input parameters and try again")
            throw e
        }
    }

    private fun syncTerms(mainValuesFile: File, projectId: Int, poEditorApiController: PoEditorApiControllerImpl, tags: List<String>) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(FileInputStream(mainValuesFile))

        val localTerms = document.documentElement.getElementsByTagName("string").asList().map {
            Term(
                term = it.attributes.getNamedItem("name").nodeValue,
                tags = tags
            )
        }.associateBy { it.term }

        val remoteTerms = poEditorApiController.getTerms(projectId).associateBy { it.term }

        val deletedTerms = (remoteTerms.keys - localTerms.keys)
        var updatedTerms = remoteTerms.map {
            if (deletedTerms.contains(it.key)) {
                it.value.copy(tags = ((it.value.tags ?: emptyList()) - tags.toSet()).distinct())
            } else {
                it.value.copy(tags = ((it.value.tags ?: emptyList()) + tags.toSet()).distinct())
            }
        }

        val updateResult = poEditorApiController.upsertTerms(
            projectId = projectId,
            fuzzyTrigger = true,
            terms = updatedTerms.filter { !it.tags.isNullOrEmpty() }
        )
        logger.lifecycle("Updated terms: $updateResult")

        updatedTerms.filter { it.tags.isNullOrEmpty() }.run {
            if (isNotEmpty()) {
                val deleteResult = poEditorApiController.deleteTerms(
                    projectId = projectId,
                    terms = this
                )
                logger.lifecycle("Deleted terms: $deleteResult")
            }
        }
    }

    private fun Collection<ProjectLanguage>.joinAndFormat(transform: ((ProjectLanguage) -> CharSequence)) =
        joinToString(separator = ", ", prefix = "[", postfix = "]", transform = transform)
}
