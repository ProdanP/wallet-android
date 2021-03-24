package com.mycelium.bequant.remote.repositories

import com.mycelium.bequant.BequantConstants.KYC_ENDPOINT
import com.mycelium.bequant.BequantPreference
import com.mycelium.bequant.remote.BequantKYCApiService
import com.mycelium.bequant.remote.NullOnEmptyConverterFactory
import com.mycelium.bequant.remote.client.RetrofitFactory.objectMapper
import com.mycelium.bequant.remote.doRequest
import com.mycelium.bequant.remote.model.*
import com.mycelium.wallet.BuildConfig
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import java.io.File
import java.util.*
import kotlin.collections.HashMap


class KYCRepository {
    fun create(scope: CoroutineScope, applicant: KYCApplicant, success: (() -> Unit),
               error: (Int, String) -> Unit, finally: (() -> Unit)? = null) {
        doRequest(scope, {
            if (BequantPreference.getKYCToken().isNotEmpty()) {
                service.update(applicant)
            } else {
                service.create(KYCCreateRequest(applicant))
            }
        }, {
            val uuid = it?.uuid ?: ""
            BequantPreference.setKYCToken(uuid)
            success()
        }, error, finally)
    }

    fun mobileVerification(scope: CoroutineScope, success: ((String) -> Unit), error: (Int, String) -> Unit, finally: () -> Unit) {
        doRequest(scope, {
            service.mobileVerification(BequantPreference.getKYCToken())
        }, {
            success(it?.message ?: "")
        }, { code, msg ->
            error(code, msg)
        }, finally)
    }

    fun checkMobileVerification(scope: CoroutineScope, code: String,
                                success: (() -> Unit), error: (() -> Unit)) {
        doRequest(scope, {
            service.checkMobileVerification(BequantPreference.getKYCToken(), code)
        }, {
            when (it?.message) {
                "CODE_VALID", "VERIFIED" -> success()
                else -> error()
            }
        }, { _, _ ->
            error()
        }, {})
    }

    fun submit(scope: CoroutineScope, success: ((response : SubmitResponse?) -> Unit),
               error: ((Int, String) -> Unit)? = null, finally: (() -> Unit)? = null) {
        Api.signRepository.accountOnceToken(scope,
                success = { onceTokenResponse ->
                    onceTokenResponse?.token?.let { onceToken ->
                        doRequest(scope, {
                            service.submit(OnceToken(onceToken))
                        }, {
                            success(it)
                        }, { code, msg ->
                            error?.invoke(code, msg)
                        }, finally)
                    }
                },
                error = { code, msg ->
                    error?.invoke(code, msg)
                    finally?.invoke()
                })
    }

    fun uploadDocument(scope: CoroutineScope, type: KYCDocument, file: File, country: String,
                       progress: ((Long, Long) -> Unit), success: () -> Unit, error: () -> Unit) {
        doRequest(scope, {
            val fileBody = ProgressRequestBody(file, "image")
            fileBody.progressListener = progress
            val multipartBody = MultipartBody.Part.createFormData("file", file.name, fileBody)
            val typeBody = RequestBody.create(MediaType.parse("text/plain"), type.toString())
            val countryBody = RequestBody.create(MediaType.parse("text/plain"), country)
            service.uploadFile(BequantPreference.getKYCToken(), typeBody, countryBody, multipartBody)
        }, {
            success()
        }, { _, _ ->
            error()
        }, {})
    }

    fun uploadDocuments(scope: CoroutineScope, fileMap: Map<File, KYCDocument>, country: String,
                        success: () -> Unit, error: (String) -> Unit, finally: (() -> Unit)? = null) {
        doRequest(scope, {
            var result: Response<KYCResponse> = Response.success(null)
            fileMap.forEach {
                val fileBody = ProgressRequestBody(it.key, "image")
                val multipartBody = MultipartBody.Part.createFormData("file", it.key.name, fileBody)
                val type = RequestBody.create(MediaType.parse("text/plain"), it.value.toString())
                val countryRequestBody = RequestBody.create(MediaType.parse("text/plain"), country)
                result = service.uploadFile(BequantPreference.getKYCToken(), type, countryRequestBody, multipartBody)
            }
            result
        }, {
            success()
        }, { _, msg ->
            error(msg)
        }, finally)
    }

    fun status(scope: CoroutineScope, success: ((StatusMessage) -> Unit),
               error: ((Int, String) -> Unit)? = null, finally: (() -> Unit)? = null) {
        doRequest(scope, {
            service.status(BequantPreference.getKYCToken())
        }, { response ->
            val oldStatus = BequantPreference.getKYCStatus()
            val status = response?.message?.global ?: KYCStatus.NONE
            BequantPreference.setKYCStatus(status)
            BequantPreference.setKYCStatusMessage(response?.message?.message ?: "")
            BequantPreference.setKYCSectionStatus(response?.message?.sections?.flatMap { it.map { it.key to it.value } })
            BequantPreference.setKYCSubmitDate(response?.message?.submitDate ?: Date(0))
            BequantPreference.setKYCSubmitted(response?.message?.submitted ?: false)

            if ((oldStatus == KYCStatus.NONE && status == KYCStatus.PENDING) || (oldStatus != KYCStatus.NONE && status != oldStatus)) {
                BequantUserEvent.KYC_STATUS_CHANGE.track()
            }

            success(response?.message!!)
        }, { code, msg ->
            error?.invoke(code, msg)
        }, finally)
    }

    fun kycToken(scope: CoroutineScope, success: ((String) -> Unit),
                 error: ((Int, String) -> Unit)? = null, finally: (() -> Unit)? = null) {
        Api.signRepository.accountOnceToken(scope,
                success = { onceTokenResponse ->
                    onceTokenResponse?.token?.let { onceToken ->
                        doRequest(scope,
                                request = {
                                    service.kycToken(onceToken)
                                   // service.kycToken("bf32a7f0-e4af-4ea7-b53a-a01df9001e38")
                                },
                                successBlock = {
                                    it?.message?.uuid?.let { uuid ->
                                        BequantPreference.setKYCToken(uuid)
                                        success(uuid)
                                    }
                                },
                                errorBlock = { code, msg ->
                                    error?.invoke(code, msg)
                                },
                                finallyBlock = finally)
                    }
                },
                error = { code, msg ->
                    error?.invoke(code, msg)
                    finally?.invoke()
                })
    }

    fun sumSubToken(scope: CoroutineScope, success: ((SumSubTokenResponse) -> Unit),
                    error: ((Int, String) -> Unit)? = null, finally: (() -> Unit)? = null) {
        Api.signRepository.accountOnceToken(scope,
                success = { onceTokenResponse ->
                    onceTokenResponse?.token?.let { onceToken ->
                        doRequest(scope,
                                request = {
                                    service.generateSumSubToken(SumSubTokenBody("basic", onceToken))
                                    //service.generateSumSubToken(SumSubTokenBody("basic", "bf32a7f0-e4af-4ea7-b53a-a01df9001e38"))
                                },
                                successBlock = {
                                    it?.let(success)
                                },
                                errorBlock = { code, msg ->
                                    error?.invoke(code, msg)
                                },
                                finallyBlock = finally)
                    }
                }, error = { code, msg ->
            error?.invoke(code, msg)
            finally?.invoke()
        })
    }

    companion object {
        val retrofitBuilder by lazy { getBuilder() }
        val service by lazy {
            retrofitBuilder
                    .build()
                    .create(BequantKYCApiService::class.java)
        }

        private fun getBuilder(): Retrofit.Builder {
            return Retrofit.Builder()
                    .baseUrl(KYC_ENDPOINT)
                    .client(OkHttpClient.Builder()
                            .addInterceptor {
                                it.proceed(it.request().newBuilder().apply {
                                    header("Content-Type", "application/json")
                                    header("x-access-token", "xyz")
                                }.build())
                            }
                            .apply {
                                if (BuildConfig.DEBUG) {
                                    addInterceptor(HttpLoggingInterceptor().apply {
                                        level = HttpLoggingInterceptor.Level.BODY
                                    })
                                }
                            }
                            .build())
                    .addConverterFactory(NullOnEmptyConverterFactory())
                    .addConverterFactory(JacksonConverterFactory.create(objectMapper))
        }
    }
}
