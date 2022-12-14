package com.example.testsecuritythierry.repositories

import com.example.testsecuritythierry.config.*
import com.example.testsecuritythierry.http.*
import com.example.testsecuritythierry.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import org.koin.core.component.KoinComponent
import java.util.*
import kotlin.system.measureTimeMillis

const val oneMinute = 60 * 1000L

/*
* Quotas in the Free VirusTotal account:
* Request rate	4 lookups / min
* Daily quota   500 lookups / day
* */
class VirusCheckerRepository: KoinComponent {

    private var virusTotalRawApiKey = ""
    // to be used wit ha x-apikey header
    private val virusTotalApiKey: String
        get() {
            // hashing makes it a little harder to steal the ApiKey from the archive
            val rawKey = "NTZjMDE$virusTotalRawApiKey=="
            val decoder: Base64.Decoder = Base64.getDecoder()
            return String(decoder.decode(rawKey))
        }

    private lateinit var api: VirusTotalApi

    fun init(virusTotalRawApiKeyIn: String) {
        virusTotalRawApiKey = virusTotalRawApiKeyIn
        createApi()
    }

    private fun createApi() = run {
        api = RetrofitHelper.getInstance(
            baseUrl = if (useAFakeUrlForVirusTotal) virusTotalBaseUrlFake else virusTotalBaseUrl,
            maxConnections = maxConcurrentConnectionsOnVirusTotal,
            requestHeader = Pair(first = "x-apikey", second = virusTotalApiKey)
        ).create(VirusTotalApi::class.java)
    }

    // We only take 4 flow tasks per minute using flatMapMerge()
    // https://stackoverflow.com/questions/58658630/parallel-request-with-retrofit-coroutines-and-suspend-functions
    @OptIn(FlowPreview::class)
    fun analyseFileHashes(
        hashes: List<String>,
        mapHashToVirusStatusHistory: MutableMap<String, AnalysisResult>): Flow<Pair<String, Any>> = hashes
        .asFlow()
        .flowOn(Dispatchers.IO)
        .flatMapMerge(concurrency = maxConcurrentConnectionsOnVirusTotal) { hash ->
            flow {
                val elapsed = measureTimeMillis {
                    try {
                        mapHashToVirusStatusHistory[hash]?.let { rememberedValue ->
                            emit(hash to rememberedValue)
                            // we do not delay because we did not use the API
                            return@flow
                        }
                        if (useAFakeUrlForVirusTotal && manuallyAddAVirus && hash == hashOfVirus1) {
                            val result = AnalysisResultVirusFound(virusTotalFile = null)
                            emit(hash to result)
                            // we do not delay because we did not use the API
                            return@flow
                        }
                        // ------> API access to analyze the file for viruses
                        val response = api.analyseFileHash(hash)
                        // code 404 means no virus
                        if (!response.isSuccessful && response.code() == 404) {
                            val result = AnalysisResultNoThreat()
                            emit(hash to result)
                            return@measureTimeMillis
                        }
                        // other error
                        if (!response.isSuccessful && response.code() != 404) {
                            val result = AnalysisResultError()
                            emit(hash to result)
                            return@measureTimeMillis
                        }
                        // virus found
                        if (response.isSuccessful) {
                            var parsedData: DataVirusTotalFile? = null
                            response.body()?.let {
                                parsedData = it
                                if (it.data.attributes.last_analysis_stats.malicious > 0) {
                                    val result = AnalysisResultVirusFound(virusTotalFile = parsedData)
                                    emit(hash to result)
                                    return@measureTimeMillis
                                }
                                // if no engine marked as malicious, then there is no threat
                                // we ignore the suspicious property
                                val result = AnalysisResultNoThreat()
                                emit(hash to result)
                                return@measureTimeMillis
                            }
                            // if we could not parse the file, we register an error
                            val result = AnalysisResultError()
                            emit(hash to result)
                            return@measureTimeMillis
                        }
                    } catch(e: Throwable) {
                        val result = AnalysisResultError()
                        emit(hash to result)
                    }
                }
                //println(elapsed)
                // we make sure the flow task takes more than one minute
                delay(maxOf(0L, oneMinute - elapsed))
            }
        }
}
