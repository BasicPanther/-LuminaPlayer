package com.example.data.service

import android.util.Log
import com.example.data.model.MediaItem
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.regex.Pattern

// Simple network models for TMDB
data class TmdbSearchResponse<T>(val results: List<T> = emptyList())
data class TmdbMovieResult(
    val id: Int = 0,
    val title: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val vote_average: Float? = null
)
data class TmdbTvResult(
    val id: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val vote_average: Float? = null
)
data class TmdbEpisodeResult(
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val air_date: String? = null,
    val vote_average: Float? = null
)

interface TmdbApi {
    @GET("search/movie")
    suspend fun searchMovie(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("year") year: String? = null
    ): TmdbSearchResponse<TmdbMovieResult>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("api_key") apiKey: String,
        @Query("query") query: String
    ): TmdbSearchResponse<TmdbTvResult>

    @GET("tv/{tv_id}/season/{season_number}/episode/{episode_number}")
    suspend fun getEpisodeDetails(
        @Path("tv_id") tvId: Int,
        @Path("season_number") season: Int,
        @Path("episode_number") episode: Int,
        @Query("api_key") apiKey: String
    ): TmdbEpisodeResult

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): TmdbMovieResult

    @GET("tv/{tv_id}")
    suspend fun getTvShowDetails(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): TmdbTvResult
}

object MetadataService {
    private const val TAG = "MetadataService"
    
    val CINEMATIC_STILLS = listOf(
        "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=800", // Theatre
        "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=800", // Cinema seats
        "https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?q=80&w=800", // Classic theatre
        "https://images.unsplash.com/photo-1478760329108-5c3ed9d495a0?q=80&w=800", // Abstract nebula
        "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?q=80&w=800", // Sci-fi space
        "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=800", // Fantasy landscape
        "https://images.unsplash.com/photo-1446776811953-b23d57bd21aa?q=80&w=800", // Earth from space
        "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=800", // Digital network
        "https://images.unsplash.com/photo-1541701494587-cb58502866ab?q=80&w=800", // Cyberpunk abstract
        "https://images.unsplash.com/photo-1518173946687-a4c8a383392c?q=80&w=800"  // Rain in forest
    )

    val api: TmdbApi by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
        retrofit.create(TmdbApi::class.java)
    }

    data class UnifiedTmdbResult(
        val id: Int,
        val title: String,
        val overview: String?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val releaseDate: String?,
        val rating: Float?,
        val type: String // "MOVIE" or "TV"
    )

    suspend fun searchTmdb(apiKey: String, query: String, type: String): List<UnifiedTmdbResult> {
        if (apiKey.isBlank() || apiKey == "MY_TMDB_API_KEY") {
            // Try to use Gemini to search!
            val gApiKey = com.example.BuildConfig.GEMINI_API_KEY
            if (gApiKey.isNotBlank() && gApiKey != "MY_GEMINI_API_KEY") {
                val geminiResults = searchWithGemini(query, type)
                if (geminiResults.isNotEmpty()) return geminiResults
            }
            // Keyless YTS search fallback for Movies!
            if (type == "MOVIE") {
                try {
                    val ytsResults = searchYts(query)
                    if (ytsResults.isNotEmpty()) return ytsResults
                } catch (e: Exception) {
                    Log.e(TAG, "YTS search fallback failed", e)
                }
                // Try TVmaze as secondary fallback for movie (TV movie match)
                try {
                    return searchTvmaze(query)
                } catch (e: Exception) {
                    Log.e(TAG, "TVmaze movie search fallback failed", e)
                }
            }
            // Keyless TVmaze search fallback for TV Shows!
            if (type == "TV") {
                try {
                    return searchTvmaze(query)
                } catch (e: Exception) {
                    Log.e(TAG, "TVmaze search failed", e)
                }
            }
            return emptyList()
        }
        return try {
            if (type == "MOVIE") {
                val resp = api.searchMovie(apiKey, query)
                resp.results.map {
                    UnifiedTmdbResult(
                        id = it.id,
                        title = it.title ?: "Untitled Movie",
                        overview = it.overview,
                        posterUrl = it.poster_path?.let { p -> "https://image.tmdb.org/t/p/w500$p" },
                        backdropUrl = it.backdrop_path?.let { b -> "https://image.tmdb.org/t/p/w1280$b" },
                        releaseDate = it.release_date,
                        rating = it.vote_average,
                        type = "MOVIE"
                    )
                }
            } else {
                val resp = api.searchTv(apiKey, query)
                resp.results.map {
                    UnifiedTmdbResult(
                        id = it.id,
                        title = it.name ?: "Untitled TV Show",
                        overview = it.overview,
                        posterUrl = it.poster_path?.let { p -> "https://image.tmdb.org/t/p/w500$p" },
                        backdropUrl = it.backdrop_path?.let { b -> "https://image.tmdb.org/t/p/w1280$b" },
                        releaseDate = it.first_air_date,
                        rating = it.vote_average,
                        type = "TV"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual TMDb search failed, falling back to Gemini", e)
            searchWithGemini(query, type)
        }
    }

    suspend fun searchTvmaze(query: String): List<UnifiedTmdbResult> = withContext(Dispatchers.IO) {
        val url = "https://api.tvmaze.com/search/shows?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONArray(bodyStr)
                val results = mutableListOf<UnifiedTmdbResult>()
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val show = entry.getJSONObject("show")
                    val showId = show.getInt("id")
                    val name = show.getString("name")
                    val summaryRaw = show.optString("summary", "")
                    val summary = summaryRaw.replace(Regex("<.*?>"), "").trim()
                    val premiered = show.optString("premiered", "2024-01-01")
                    val imageObj = show.optJSONObject("image")
                    val posterUrl = imageObj?.optString("original") ?: imageObj?.optString("medium")
                    val ratingObj = show.optJSONObject("rating")
                    val rating = ratingObj?.optDouble("average", 7.0)?.toFloat() ?: 7.0f
                    
                    results.add(
                        UnifiedTmdbResult(
                            id = showId,
                            title = name,
                            overview = summary,
                            posterUrl = posterUrl ?: "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=400",
                            backdropUrl = posterUrl ?: "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=1200",
                            releaseDate = premiered,
                            rating = rating,
                            type = "TV"
                        )
                    )
                }
                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "TVmaze search error", e)
            emptyList()
        }
    }

    suspend fun searchYts(query: String): List<UnifiedTmdbResult> = withContext(Dispatchers.IO) {
        val url = "https://yts.mx/api/v2/list_movies.json?query_term=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val obj = JSONObject(bodyStr)
                if (obj.getString("status") != "ok") return@withContext emptyList()
                val data = obj.optJSONObject("data") ?: return@withContext emptyList()
                val moviesArr = data.optJSONArray("movies") ?: return@withContext emptyList()
                val results = mutableListOf<UnifiedTmdbResult>()
                for (i in 0 until moviesArr.length()) {
                    val m = moviesArr.getJSONObject(i)
                    val id = m.getInt("id")
                    val title = m.getString("title")
                    val rating = m.optDouble("rating", 7.0).toFloat()
                    val summary = m.optString("synopsis").takeIf { it.isNotBlank() }
                        ?: m.optString("summary", "")
                    val poster = m.optString("medium_cover_image")
                    val backdrop = m.optString("background_image_original") ?: m.optString("background_image")
                    val mYear = m.optInt("year", 2024).toString()
                    
                    results.add(
                        UnifiedTmdbResult(
                            id = id,
                            title = title,
                            overview = summary,
                            posterUrl = poster.takeIf { !it.isNullOrBlank() },
                            backdropUrl = backdrop.takeIf { !it.isNullOrBlank() },
                            releaseDate = mYear,
                            rating = rating,
                            type = "MOVIE"
                        )
                    )
                }
                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "YTS search failed for movie query: $query", e)
            emptyList()
        }
    }

    data class TvmazeEpisodeDetails(
        val name: String,
        val overview: String?,
        val stillUrl: String?
    )

    suspend fun fetchEpisodeFromTvmaze(showId: Int, season: Int, episode: Int): TvmazeEpisodeDetails? = withContext(Dispatchers.IO) {
        val url = "https://api.tvmaze.com/shows/$showId/episodebynumber?season=$season&number=$episode"
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val epObj = JSONObject(bodyStr)
                val name = epObj.getString("name")
                val rawSummary = epObj.optString("summary", "")
                val summary = rawSummary.replace(Regex("<.*?>"), "").trim()
                val imageObj = epObj.optJSONObject("image")
                val stillUrl = imageObj?.optString("original") ?: imageObj?.optString("medium")
                TvmazeEpisodeDetails(name = name, overview = summary, stillUrl = stillUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TVmaze episode fetch failed for show $showId S${season}E${episode}", e)
            null
        }
    }

    suspend fun fetchFromTvmaze(
        showName: String,
        season: Int,
        episode: Int,
        filePath: String,
        fileName: String,
        folderPath: String,
        id: String
    ): MediaItem? = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        
        fun queryTvmaze(query: String): JSONObject? {
            val url = "https://api.tvmaze.com/singlesearch/shows?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val request = Request.Builder().url(url).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        if (!bodyStr.isNullOrBlank()) {
                            return JSONObject(bodyStr)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TVMaze singlesearch failed for query: $query", e)
            }
            return null
        }

        var showObj = queryTvmaze(showName)
        
        // Retry 1: Strip 4-digit years (e.g. "Hardy Boys 2020" -> "Hardy Boys")
        if (showObj == null) {
            val yearRegex = "\\b(19\\d\\d|20\\d\\d)\\b".toRegex()
            if (yearRegex.containsMatchIn(showName)) {
                val cleanShowName = showName.replace(yearRegex, "").replace(Regex("\\s+"), " ").trim()
                if (cleanShowName.isNotEmpty() && cleanShowName != showName) {
                    showObj = queryTvmaze(cleanShowName)
                }
            }
        }
        
        // Retry 2: Strip campaign/subtitle additions (e.g. "Dimension 20 Fantasy High" -> "Dimension 20")
        if (showObj == null) {
            val separators = listOf(" -", " :", ":", "-")
            val idx = showName.indexOfAny(separators)
            if (idx > 1) {
                val truncated = showName.substring(0, idx).trim()
                if (truncated.isNotEmpty() && truncated != showName) {
                    showObj = queryTvmaze(truncated)
                }
            }
        }

        if (showObj == null) return@withContext null

        try {
            val showId = showObj.getInt("id")
            val realShowName = showObj.getString("name")
            val premiered = showObj.optString("premiered", "2024-01-01")
            val ratingObj = showObj.optJSONObject("rating")
            val rating = ratingObj?.optDouble("average", 7.0)?.toFloat() ?: 7.0f
            
            val imageObj = showObj.optJSONObject("image")
            val posterUrl = imageObj?.optString("original") ?: imageObj?.optString("medium")
            
            val rawSummary = showObj.optString("summary", "")
            val showSummary = rawSummary.replace(Regex("<.*?>"), "").trim()
            
            var castStr: String? = null
            var directorStr: String? = null
            
            try {
                val castUrl = "https://api.tvmaze.com/shows/$showId/cast"
                val castRequest = Request.Builder().url(castUrl).build()
                client.newCall(castRequest).execute().use { cResp ->
                    if (cResp.isSuccessful) {
                        val cBody = cResp.body?.string()
                        if (!cBody.isNullOrBlank()) {
                            val cArr = JSONArray(cBody)
                            val castList = mutableListOf<String>()
                            for (j in 0 until minOf(cArr.length(), 5)) {
                                val person = cArr.getJSONObject(j).optJSONObject("person")
                                val name = person?.optString("name")
                                if (!name.isNullOrBlank()) {
                                    castList.add(name)
                                }
                            }
                            if (castList.isNotEmpty()) {
                                castStr = castList.joinToString(", ")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TVmaze cast fetch failed", e)
            }

            try {
                val crewUrl = "https://api.tvmaze.com/shows/$showId/crew"
                val crewRequest = Request.Builder().url(crewUrl).build()
                client.newCall(crewRequest).execute().use { crResp ->
                    if (crResp.isSuccessful) {
                        val crBody = crResp.body?.string()
                        if (!crBody.isNullOrBlank()) {
                            val crArr = JSONArray(crBody)
                            val creators = mutableListOf<String>()
                            val directors = mutableListOf<String>()
                            for (j in 0 until crArr.length()) {
                                val member = crArr.getJSONObject(j)
                                val type = member.optString("type")
                                val person = member.optJSONObject("person")
                                val name = person?.optString("name")
                                if (!name.isNullOrBlank()) {
                                    if (type == "Creator") {
                                        creators.add(name)
                                    } else if (type == "Director") {
                                        directors.add(name)
                                    }
                                }
                            }
                            val finalDirectors = if (creators.isNotEmpty()) creators else directors
                            if (finalDirectors.isNotEmpty()) {
                                directorStr = finalDirectors.take(3).joinToString(", ")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TVmaze crew fetch failed", e)
            }

            val epUrl = "https://api.tvmaze.com/shows/$showId/episodebynumber?season=$season&number=$episode"
            val epRequest = Request.Builder().url(epUrl).build()
            client.newCall(epRequest).execute().use { epResponse ->
                if (!epResponse.isSuccessful) {
                    return@withContext MediaItem(
                        id = id,
                        filePath = filePath,
                        fileName = fileName,
                        title = "$realShowName - S${String.format("%02d", season)}E${String.format("%02d", episode)}",
                        type = "SHOW_EPISODE",
                        showName = realShowName,
                        season = season,
                        episodeNumber = episode,
                        overview = showSummary,
                        releaseDate = premiered,
                        rating = rating,
                        posterUrl = posterUrl ?: "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=400",
                        backdropUrl = posterUrl ?: "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=1200",
                        folderPath = folderPath,
                        cast = castStr,
                        director = directorStr
                    )
                }
                val epBodyStr = epResponse.body?.string() ?: return@withContext null
                val epObj = JSONObject(epBodyStr)
                val epName = epObj.getString("name")
                val epRawSummary = epObj.optString("summary", "")
                val epSummary = epRawSummary.replace(Regex("<.*?>"), "").trim()
                val epAirdate = epObj.optString("airdate", premiered)
                
                val epImageObj = epObj.optJSONObject("image")
                val epBackdropUrl = epImageObj?.optString("original") ?: epImageObj?.optString("medium")
                
                MediaItem(
                    id = id,
                    filePath = filePath,
                    fileName = fileName,
                    title = "$realShowName - S${String.format("%02d", season)}E${String.format("%02d", episode)}: $epName",
                    type = "SHOW_EPISODE",
                    showName = realShowName,
                    season = season,
                    episodeNumber = episode,
                    overview = if (epSummary.isNotBlank()) epSummary else showSummary,
                    releaseDate = epAirdate,
                    rating = rating,
                    posterUrl = posterUrl ?: "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=400",
                    backdropUrl = epBackdropUrl ?: posterUrl ?: "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=1200",
                    folderPath = folderPath,
                    cast = castStr,
                    director = directorStr
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "TVmaze single search failed for show $showName", e)
            null
        }
    }

    suspend fun fetchFromYts(
        movieTitle: String,
        year: Int?,
        filePath: String,
        fileName: String,
        folderPath: String,
        id: String
    ): MediaItem? = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        
        fun queryYts(query: String): JSONObject? {
            val url = "https://yts.mx/api/v2/list_movies.json?query_term=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val request = Request.Builder().url(url).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        if (!bodyStr.isNullOrBlank()) {
                            val obj = JSONObject(bodyStr)
                            if (obj.optString("status") == "ok") {
                                val data = obj.optJSONObject("data")
                                val moviesArr = data?.optJSONArray("movies")
                                if (moviesArr != null && moviesArr.length() > 0) {
                                    return obj
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "YTS query failed for: $query", e)
            }
            return null
        }

        var obj = queryYts(movieTitle)
        
        // Retry 1: If failed, try stripping common separators/subtitles
        if (obj == null) {
            val separators = listOf(" -", " :", ":", "-")
            val idx = movieTitle.indexOfAny(separators)
            if (idx > 1) {
                val truncated = movieTitle.substring(0, idx).trim()
                if (truncated.isNotEmpty() && truncated != movieTitle) {
                    obj = queryYts(truncated)
                }
            }
        }

        if (obj == null) return@withContext null

        try {
            val data = obj.optJSONObject("data") ?: return@withContext null
            val moviesArr = data.optJSONArray("movies") ?: return@withContext null
            if (moviesArr.length() == 0) return@withContext null
            
            // Prioritize year matching if provided
            var matchedMovie: JSONObject? = null
            if (year != null) {
                for (i in 0 until moviesArr.length()) {
                    val m = moviesArr.getJSONObject(i)
                    if (m.optInt("year") == year) {
                        matchedMovie = m
                        break
                    }
                }
            }
            
            // Fallback to first result
            if (matchedMovie == null) {
                matchedMovie = moviesArr.getJSONObject(0)
            }
            
            val title = matchedMovie.getString("title")
            val rating = matchedMovie.optDouble("rating", 7.0).toFloat()
            val summary = matchedMovie.optString("synopsis").takeIf { it.isNotBlank() }
                ?: matchedMovie.optString("summary", "")
            val poster = matchedMovie.optString("medium_cover_image")
            val backdrop = matchedMovie.optString("background_image_original") ?: matchedMovie.optString("background_image")
            val mYear = matchedMovie.optInt("year", 2024).toString()
            
            val movieId = matchedMovie.optInt("id", 0)
            var castStr: String? = null
            if (movieId != 0) {
                try {
                    val detailUrl = "https://yts.mx/api/v2/movie_details.json?movie_id=$movieId&with_cast=true"
                    val detailReq = Request.Builder().url(detailUrl).build()
                    client.newCall(detailReq).execute().use { dResp ->
                        if (dResp.isSuccessful) {
                            val dBody = dResp.body?.string()
                            if (!dBody.isNullOrBlank()) {
                                val dObj = JSONObject(dBody)
                                val dData = dObj.optJSONObject("data")
                                val dMovie = dData?.optJSONObject("movie")
                                val castArr = dMovie?.optJSONArray("cast")
                                if (castArr != null && castArr.length() > 0) {
                                    val castList = mutableListOf<String>()
                                    for (j in 0 until minOf(castArr.length(), 5)) {
                                        val castMember = castArr.getJSONObject(j)
                                        val name = castMember.optString("name")
                                        if (name.isNotBlank()) {
                                            castList.add(name)
                                        }
                                    }
                                    if (castList.isNotEmpty()) {
                                        castStr = castList.joinToString(", ")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: java.lang.Exception) {
                    Log.e(TAG, "YTS cast details fetch failed", e)
                }
            }

            MediaItem(
                id = id,
                filePath = filePath,
                fileName = fileName,
                title = title,
                type = "MOVIE",
                overview = summary,
                releaseDate = mYear,
                rating = rating,
                posterUrl = poster.takeIf { !it.isNullOrBlank() } ?: "https://images.unsplash.com/photo-1534447677768-be436bb09401?q=80&w=400",
                backdropUrl = backdrop.takeIf { !it.isNullOrBlank() } ?: "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=1200",
                folderPath = folderPath,
                cast = castStr,
                director = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "YTS fallback processing failed for movie $movieTitle", e)
            null
        }
    }

    suspend fun searchWithGemini(query: String, type: String): List<UnifiedTmdbResult> = withContext(Dispatchers.IO) {
        val gApiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (gApiKey.isBlank() || gApiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API key is blank/missing")
            return@withContext emptyList()
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$gApiKey"

        val prompt = """
            Search for 1 to 3 best matching ${if (type == "MOVIE") "movies" else "TV shows"} for the query "$query".
            Return a valid JSON array representing the search results, where each element matches this schema exactly:
            {
              "id": 12345, // an actual TMDB ID if known, otherwise a unique random int
              "title": "Official Title of Movie or TV Show",
              "overview": "A brief 2-3 sentence overview.",
              "releaseDate": "YYYY-MM-DD",
              "rating": 7.5,
              "posterUrl": "If you know the TMDB poster path, provide the full URL (e.g. 'https://image.tmdb.org/t/p/w500/or9760gS778g6VEvS969g867XN.jpg'). Otherwise, provide a beautiful high-quality Unsplash cinematic photo URL (e.g. 'https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=500')",
              "backdropUrl": "If you know the TMDB backdrop path, provide the full URL. Otherwise, provide a beautiful cinematic Unsplash backdrop photo URL."
            }
            Ensure your response is valid JSON only.
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini search request failed: ${response.code}")
                    return@withContext emptyList()
                }
                val respStr = response.body?.string() ?: return@withContext emptyList()
                val respObj = JSONObject(respStr)
                val candidates = respObj.getJSONArray("candidates")
                if (candidates.length() == 0) return@withContext emptyList()
                val text = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val arr = JSONArray(text)
                val list = mutableListOf<UnifiedTmdbResult>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        UnifiedTmdbResult(
                            id = obj.optInt("id", (query + i).hashCode()),
                            title = obj.getString("title"),
                            overview = obj.optString("overview", "A beautifully matched media catalog entry."),
                            posterUrl = obj.optString("posterUrl", "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=500"),
                            backdropUrl = obj.optString("backdropUrl", "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=1200"),
                            releaseDate = obj.optString("releaseDate", "2024-01-01"),
                            rating = obj.optDouble("rating", 7.0).toFloat(),
                            type = type
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini search parsing failed", e)
            emptyList()
        }
    }

    suspend fun fetchFromGemini(
        fileName: String,
        filePath: String,
        folderPath: String,
        id: String
    ): MediaItem? = withContext(Dispatchers.IO) {
        val gApiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (gApiKey.isBlank() || gApiKey == "MY_GEMINI_API_KEY") {
            return@withContext null
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$gApiKey"

        val prompt = """
            Identify the movie or TV show episode from this filename: "$fileName".
            Generate high-quality metadata for it, prioritizing accurate real-world details (like real scores/ratings, cast, and directors).
            Return a valid JSON object representing the metadata with the following schema:
            {
              "title": "Official Title of Movie or TV Show (or Episode Title if a TV Show)",
              "type": "MOVIE" or "SHOW_EPISODE",
              "showName": "If TV Show, the TV Show name (otherwise null)",
              "season": 1, // If TV Show, the season number (otherwise null)
              "episodeNumber": 1, // If TV Show, the episode number (otherwise null)
              "overview": "A brief 2-3 sentence captivating overview.",
              "releaseDate": "YYYY-MM-DD",
              "rating": 7.5, // Return the actual real score/rating if known (e.g., from TMDB, IMDb, etc.), on a 1.0-10.0 scale.
              "posterUrl": "If you know the TMDB poster path, provide the full URL (e.g. 'https://image.tmdb.org/t/p/w500/or9760gS778g6VEvS969g867XN.jpg'). Otherwise, provide a beautiful high-quality Unsplash cinematic photo URL (e.g. 'https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=500')",
              "backdropUrl": "If you know the TMDB backdrop path, provide the full URL. Otherwise, provide a beautiful cinematic Unsplash backdrop photo URL.",
              "cast": "A comma-separated list of major cast/actors (e.g., 'Keanu Reeves, Laurence Fishburne, Carrie-Anne Moss')",
              "director": "The director's name (e.g., 'Lana Wachowski, Lilly Wachowski')"
            }
            Ensure your response is valid JSON only.
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini fetch request failed: ${response.code}")
                    return@withContext null
                }
                val respStr = response.body?.string() ?: return@withContext null
                val respObj = JSONObject(respStr)
                val candidates = respObj.getJSONArray("candidates")
                if (candidates.length() == 0) return@withContext null
                val text = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val metaObj = JSONObject(text)
                val type = metaObj.optString("type", "MOVIE")
                val title = metaObj.getString("title")
                val showName = if (metaObj.isNull("showName")) null else metaObj.optString("showName", null)
                val season = if (metaObj.isNull("season")) null else metaObj.optInt("season")
                val episodeNumber = if (metaObj.isNull("episodeNumber")) null else metaObj.optInt("episodeNumber")
                val overview = metaObj.optString("overview", "A locally indexed media file.")
                val releaseDate = metaObj.optString("releaseDate", "2024-01-01")
                val rating = metaObj.optDouble("rating", 7.0).toFloat()
                val posterUrl = metaObj.optString("posterUrl", "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=500")
                val backdropUrl = metaObj.optString("backdropUrl", "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=1200")
                val cast = if (metaObj.isNull("cast")) null else metaObj.optString("cast", null)
                val director = if (metaObj.isNull("director")) null else metaObj.optString("director", null)

                MediaItem(
                    id = id,
                    filePath = filePath,
                    fileName = fileName,
                    title = title,
                    type = type,
                    showName = showName,
                    season = season,
                    episodeNumber = episodeNumber,
                    overview = overview,
                    releaseDate = releaseDate,
                    rating = rating,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    folderPath = folderPath,
                    cast = cast,
                    director = director
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini fetch parsing failed", e)
            null
        }
    }

    suspend fun fetchByIdFromGemini(tmdbId: Int, type: String): UnifiedTmdbResult? = withContext(Dispatchers.IO) {
        val gApiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (gApiKey.isBlank() || gApiKey == "MY_GEMINI_API_KEY") {
            return@withContext null
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$gApiKey"

        val prompt = """
            Fetch detailed metadata for the TMDB ID $tmdbId of type "$type".
            Return a valid JSON object matching this schema exactly:
            {
              "title": "Official Title of Movie or TV Show",
              "overview": "A brief 2-3 sentence overview.",
              "releaseDate": "YYYY-MM-DD",
              "rating": 7.5,
              "posterUrl": "If you know the TMDB poster path, provide the full URL (e.g. 'https://image.tmdb.org/t/p/w500/or9760gS778g6VEvS969g867XN.jpg'). Otherwise, provide a beautiful high-quality Unsplash cinematic photo URL.",
              "backdropUrl": "If you know the TMDB backdrop path, provide the full URL. Otherwise, provide a beautiful cinematic Unsplash backdrop photo URL."
            }
            Ensure your response is valid JSON only.
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val respStr = response.body?.string() ?: return@withContext null
                val respObj = JSONObject(respStr)
                val candidates = respObj.getJSONArray("candidates")
                if (candidates.length() == 0) return@withContext null
                val text = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val obj = JSONObject(text)
                UnifiedTmdbResult(
                    id = tmdbId,
                    title = obj.getString("title"),
                    overview = obj.optString("overview", "A beautifully matched media catalog entry."),
                    posterUrl = obj.optString("posterUrl", "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=500"),
                    backdropUrl = obj.optString("backdropUrl", "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=1200"),
                    releaseDate = obj.optString("releaseDate", "2024-01-01"),
                    rating = obj.optDouble("rating", 7.0).toFloat(),
                    type = type
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini fetch by ID failed", e)
            null
        }
    }

    // Filename parser details
    data class ParsedFilename(
        val cleanTitle: String,
        val type: String, // "MOVIE" or "SHOW_EPISODE"
        val year: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    fun parseFilename(fileName: String): ParsedFilename {
        // Strip extension
        val nameWithoutExt = fileName.substringBeforeLast(".")
        
        // Clean brackets [...] and parentheses (...) which typically contain release groups/codecs
        val cleanedBrackets = nameWithoutExt
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .trim()
        
        // Clean up common separators like . or _ or -
        val normalized = cleanedBrackets.replace(".", " ").replace("_", " ").replace("-", " ")

        // Support list of tags to clean from show/movie names
        val tags = listOf(
            "1080p", "720p", "4k", "2160p", "bluray", "webrip", "h264", "h265", "hevc", "x264", "x265", "dts", "aac", "hdr",
            "web-dl", "web", "brrip", "hdrip", "aac2.0", "xvid", "ac3", "subbed", "dubbed", "dual-audio", "dual", "multi", "multisubs", "directors-cut", "directors.cut", "extended"
        )

        // Helper to clean year and tags from show names
        fun cleanShowTitle(rawName: String): Pair<String, String?> {
            var name = rawName
            val yearPattern = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b")
            val yearMatcher = yearPattern.matcher(name)
            var year: String? = null
            if (yearMatcher.find()) {
                year = yearMatcher.group(1)
                name = yearMatcher.replaceAll("").trim()
            }
            tags.forEach { tag ->
                val tagPattern = Pattern.compile("(?i)\\b$tag\\b")
                name = tagPattern.matcher(name).replaceAll("")
            }
            name = name.replace("\\s+".toRegex(), " ").trim()
            
            // Smarter multi-season show/campaign normalizations (e.g. Dimension 20 or Hardy Boys)
            val nameLower = name.lowercase()
            if (nameLower.contains("dimension 20")) {
                return Pair("Dimension 20", year)
            }
            if (nameLower.contains("hardy boys")) {
                return Pair("The Hardy Boys", year ?: "2020")
            }
            
            return Pair(if (name.isEmpty()) rawName else name, year)
        }

        // 1. Check for S01E01 style patterns
        val tvPattern = Pattern.compile("(?i)(.*?)\\s+S(\\d+)E(\\d+)", Pattern.CASE_INSENSITIVE)
        val tvMatcher = tvPattern.matcher(normalized)
        if (tvMatcher.find()) {
            val rawShowName = tvMatcher.group(1)?.trim() ?: normalized
            val (cleanName, year) = cleanShowTitle(rawShowName)
            val season = tvMatcher.group(2)?.toIntOrNull() ?: 1
            val episode = tvMatcher.group(3)?.toIntOrNull() ?: 1
            return ParsedFilename(
                cleanTitle = cleanName,
                type = "SHOW_EPISODE",
                season = season,
                episode = episode,
                year = year
            )
        }

        // 2. Check for S01 E01 style patterns (with spaces)
        val tvPatternSpaced = Pattern.compile("(?i)(.*?)\\s+S(\\d+)\\s+E(\\d+)", Pattern.CASE_INSENSITIVE)
        val tvMatcherSpaced = tvPatternSpaced.matcher(normalized)
        if (tvMatcherSpaced.find()) {
            val rawShowName = tvMatcherSpaced.group(1)?.trim() ?: normalized
            val (cleanName, year) = cleanShowTitle(rawShowName)
            val season = tvMatcherSpaced.group(2)?.toIntOrNull() ?: 1
            val episode = tvMatcherSpaced.group(3)?.toIntOrNull() ?: 1
            return ParsedFilename(
                cleanTitle = cleanName,
                type = "SHOW_EPISODE",
                season = season,
                episode = episode,
                year = year
            )
        }

        // 3. Check for 1x01 style patterns
        val tvPattern2 = Pattern.compile("(?i)(.*?)\\s+(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE)
        val tvMatcher2 = tvPattern2.matcher(normalized)
        if (tvMatcher2.find()) {
            val rawShowName = tvMatcher2.group(1)?.trim() ?: normalized
            val (cleanName, year) = cleanShowTitle(rawShowName)
            val season = tvMatcher2.group(2)?.toIntOrNull() ?: 1
            val episode = tvMatcher2.group(3)?.toIntOrNull() ?: 1
            return ParsedFilename(
                cleanTitle = cleanName,
                type = "SHOW_EPISODE",
                season = season,
                episode = episode,
                year = year
            )
        }

        // 4. Check for Episode-only patterns like E01, Ep01, Episode 01, Ep 01
        val tvPatternEpOnly = Pattern.compile("(?i)(.*?)\\s+(?:Episode|Ep|E)\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
        val tvMatcherEpOnly = tvPatternEpOnly.matcher(normalized)
        if (tvMatcherEpOnly.find()) {
            val rawShowName = tvMatcherEpOnly.group(1)?.trim() ?: normalized
            val (cleanName, year) = cleanShowTitle(rawShowName)
            val episode = tvMatcherEpOnly.group(2)?.toIntOrNull() ?: 1
            return ParsedFilename(
                cleanTitle = cleanName,
                type = "SHOW_EPISODE",
                season = 1,
                episode = episode,
                year = year
            )
        }

        // Check for year (standard movie parsing fallback)
        val yearPattern = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b")
        val yearMatcher = yearPattern.matcher(normalized)
        var year: String? = null
        var title = normalized
        if (yearMatcher.find()) {
            year = yearMatcher.group(1)
            // Trim title to everything before the year
            val yearIndex = normalized.indexOf(year!!)
            if (yearIndex > 1) {
                title = normalized.substring(0, yearIndex).trim()
            }
        }

        var cleanTitle = title
        tags.forEach { tag ->
            val tagPattern = Pattern.compile("(?i)\\b$tag\\b")
            cleanTitle = tagPattern.matcher(cleanTitle).replaceAll("")
        }
        
        // Final clean of multiple spaces
        cleanTitle = cleanTitle.replace("\\s+".toRegex(), " ").trim()

        return ParsedFilename(
            cleanTitle = if (cleanTitle.isEmpty()) normalized else cleanTitle,
            type = "MOVIE",
            year = year
        )
    }

    suspend fun fetchMetadata(
        fileName: String,
        filePath: String,
        folderPath: String,
        apiKey: String? = null,
        omdbApiKey: String? = null
    ): MediaItem {
        val parsed = parseFilename(fileName)
        val id = filePath.hashCode().toString()
        val nameLower = fileName.lowercase()
        val isRandom = listOf(
            "vid_", "pxl_", "img_", "whatsapp", "zoom", "meeting_", 
            "screen-capture", "screencast", "recording", "camera", "untitled", "test", "dummy", "capture"
        ).any { nameLower.contains(it) }

        val defaultType = if (isRandom) "OTHER" else parsed.type
        val defaultParsed = parsed.copy(type = defaultType)

        val activeOmdbKey = if (!omdbApiKey.isNullOrBlank() && omdbApiKey != "MY_OMDB_API_KEY") {
            omdbApiKey
        } else if (com.example.BuildConfig.OMDB_API_KEY.isNotBlank() && com.example.BuildConfig.OMDB_API_KEY != "MY_OMDB_API_KEY") {
            com.example.BuildConfig.OMDB_API_KEY
        } else {
            null
        }

        if (activeOmdbKey != null && !isRandom && defaultParsed.type == "MOVIE") {
            try {
                val omdbItem = fetchFromOmdb(
                    fileName = fileName,
                    filePath = filePath,
                    folderPath = folderPath,
                    omdbApiKey = activeOmdbKey,
                    id = id
                )
                if (omdbItem != null) return omdbItem
            } catch (e: Exception) {
                Log.e(TAG, "OMDB metadata fetch failed", e)
            }
        }

        if (isRandom) {
            val gApiKey = com.example.BuildConfig.GEMINI_API_KEY
            if (gApiKey.isNotBlank() && gApiKey != "MY_GEMINI_API_KEY") {
                return fetchFromGemini(fileName, filePath, folderPath, id)
                    ?: getFallbackMetadata(defaultParsed, filePath, fileName, folderPath, id)
            }
            return getFallbackMetadata(defaultParsed, filePath, fileName, folderPath, id)
        }

        if (apiKey.isNullOrBlank()) {
            // Apply high-fidelity Gemini auto-matching if configured
            val gApiKey = com.example.BuildConfig.GEMINI_API_KEY
            if (gApiKey.isNotBlank() && gApiKey != "MY_GEMINI_API_KEY") {
                val gem = fetchFromGemini(fileName, filePath, folderPath, id)
                if (gem != null) return gem
            }
            
            // Keyless YTS auto-matching fallback for Movies!
            if (defaultParsed.type == "MOVIE") {
                try {
                    val ytsItem = fetchFromYts(
                        movieTitle = defaultParsed.cleanTitle,
                        year = defaultParsed.year?.toIntOrNull(),
                        filePath = filePath,
                        fileName = fileName,
                        folderPath = folderPath,
                        id = id
                    )
                    if (ytsItem != null) return ytsItem
                } catch (e: Exception) {
                    Log.e(TAG, "YTS fallback search failed", e)
                }
            }

            // Keyless TVmaze auto-matching fallback for TV show episodes!
            if (defaultParsed.type == "SHOW_EPISODE") {
                val tvShowName = defaultParsed.cleanTitle
                val tvSeason = defaultParsed.season ?: 1
                val tvEpisode = defaultParsed.episode ?: 1
                try {
                    val tvmazeItem = fetchFromTvmaze(
                        showName = tvShowName,
                        season = tvSeason,
                        episode = tvEpisode,
                        filePath = filePath,
                        fileName = fileName,
                        folderPath = folderPath,
                        id = id
                    )
                    if (tvmazeItem != null) return tvmazeItem
                } catch (e: Exception) {
                    Log.e(TAG, "TVmaze metadata auto-fetch failed for $tvShowName S${tvSeason}E${tvEpisode}", e)
                }
            }
            
            return getFallbackMetadata(defaultParsed, filePath, fileName, folderPath, id)
        }

        return try {
            if (defaultParsed.type == "MOVIE") {
                var response = api.searchMovie(apiKey, defaultParsed.cleanTitle, defaultParsed.year)
                var result = response.results.firstOrNull()
                
                // Retry movie search without year filter if first search returned empty (common for release mismatches)
                if (result == null && defaultParsed.year != null) {
                    val retryResponse = api.searchMovie(apiKey, defaultParsed.cleanTitle, null)
                    result = retryResponse.results.firstOrNull()
                }
                
                if (result != null) {
                    MediaItem(
                        id = id,
                        filePath = filePath,
                        fileName = fileName,
                        title = result.title ?: "Untitled Movie",
                        type = "MOVIE",
                        overview = result.overview,
                        releaseDate = result.release_date,
                        rating = result.vote_average,
                        posterUrl = result.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                        backdropUrl = result.backdrop_path?.let { "https://image.tmdb.org/t/p/w1280$it" },
                        folderPath = folderPath
                    )
                } else {
                    // Try keyless YTS safety net as secondary fallback
                    fetchFromYts(
                        movieTitle = defaultParsed.cleanTitle,
                        year = defaultParsed.year?.toIntOrNull(),
                        filePath = filePath,
                        fileName = fileName,
                        folderPath = folderPath,
                        id = id
                    ) ?: getFallbackMetadata(defaultParsed, filePath, fileName, folderPath, id)
                }
            } else {
                // Show Episode
                val tvResponse = api.searchTv(apiKey, defaultParsed.cleanTitle)
                val tvResult = tvResponse.results.firstOrNull()
                if (tvResult != null) {
                    val epResult = try {
                        api.getEpisodeDetails(
                            tvId = tvResult.id,
                            season = defaultParsed.season ?: 1,
                            episode = defaultParsed.episode ?: 1,
                            apiKey = apiKey
                        )
                    } catch (e: Exception) {
                        null
                    }

                    MediaItem(
                        id = id,
                        filePath = filePath,
                        fileName = fileName,
                        title = epResult?.name ?: "${defaultParsed.cleanTitle} - S${defaultParsed.season}E${defaultParsed.episode}",
                        type = "SHOW_EPISODE",
                        showName = tvResult.name,
                        season = defaultParsed.season,
                        episodeNumber = defaultParsed.episode,
                        overview = epResult?.overview ?: tvResult.overview,
                        releaseDate = epResult?.air_date ?: tvResult.first_air_date,
                        rating = epResult?.vote_average ?: tvResult.vote_average,
                        posterUrl = tvResult.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                        backdropUrl = epResult?.still_path?.let { "https://image.tmdb.org/t/p/w1280$it" } ?: tvResult.backdrop_path?.let { "https://image.tmdb.org/t/p/w1280$it" },
                        folderPath = folderPath
                    )
                } else {
                    // Try keyless TVmaze safety net as secondary fallback
                    val tvShowName = defaultParsed.cleanTitle
                    val tvSeason = defaultParsed.season ?: 1
                    val tvEpisode = defaultParsed.episode ?: 1
                    fetchFromTvmaze(
                        showName = tvShowName,
                        season = tvSeason,
                        episode = tvEpisode,
                        filePath = filePath,
                        fileName = fileName,
                        folderPath = folderPath,
                        id = id
                    ) ?: getFallbackMetadata(defaultParsed, filePath, fileName, folderPath, id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying TMDB API, executing keyless safety nets: ", e)
            if (defaultParsed.type == "MOVIE") {
                fetchFromYts(
                    movieTitle = defaultParsed.cleanTitle,
                    year = defaultParsed.year?.toIntOrNull(),
                    filePath = filePath,
                    fileName = fileName,
                    folderPath = folderPath,
                    id = id
                ) ?: getFallbackMetadata(defaultParsed, filePath, fileName, folderPath, id)
            } else {
                val tvShowName = defaultParsed.cleanTitle
                val tvSeason = defaultParsed.season ?: 1
                val tvEpisode = defaultParsed.episode ?: 1
                fetchFromTvmaze(
                    showName = tvShowName,
                    season = tvSeason,
                    episode = tvEpisode,
                    filePath = filePath,
                    fileName = fileName,
                    folderPath = folderPath,
                    id = id
                ) ?: getFallbackMetadata(defaultParsed, filePath, fileName, folderPath, id)
            }
        }
    }

    private data class FallbackData(
        val poster: String,
        val backdrop: String,
        val overview: String,
        val rating: String,
        val releaseDate: String,
        val finalTitle: String
    )

    private fun getFallbackMetadata(
        parsed: ParsedFilename,
        filePath: String,
        fileName: String,
        folderPath: String,
        id: String
    ): MediaItem {
        val titleLower = parsed.cleanTitle.lowercase()

        // Match well-known test films/series to generate an aesthetic out-of-box experience
        val fallback = when {
            titleLower.contains("sintel") -> {
                FallbackData(
                    poster = "https://images.unsplash.com/photo-1534447677768-be436bb09401?q=80&w=400", // gorgeous dark art
                    backdrop = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=1200",
                    overview = "The story follows a lonely young woman named Sintel who rescues and befriends a tiny dragon whom she names Scales. When Scales is snatched by an adult dragon, Sintel embarks on a long and dangerous journey to rescue him.",
                    rating = "7.8",
                    releaseDate = "2010-09-27",
                    finalTitle = "Sintel"
                )
            }
            titleLower.contains("bunny") || titleLower.contains("big buck") -> {
                FallbackData(
                    poster = "https://images.unsplash.com/photo-1507679799987-c73779587ccf?q=80&w=400",
                    backdrop = "https://images.unsplash.com/photo-1478760329108-5c3ed9d495a0?q=80&w=1200",
                    overview = "A giant, lovable rabbit named Big Buck Bunny wakes up in his forest home only to have his peaceful morning ruined by three mischievous rodents who start harassing the local wildlife.",
                    rating = "7.2",
                    releaseDate = "2008-05-30",
                    finalTitle = "Big Buck Bunny"
                )
            }
            titleLower.contains("tears") || titleLower.contains("steel") -> {
                FallbackData(
                    poster = "https://images.unsplash.com/photo-1478760329108-5c3ed9d495a0?q=80&w=400",
                    backdrop = "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?q=80&w=1200",
                    overview = "Set in a dystopian future Amsterdam, a group of scientists attempts to save the city from rampaging giant robots using a cybernetic time-distortion machine.",
                    rating = "6.9",
                    releaseDate = "2012-10-12",
                    finalTitle = "Tears of Steel"
                )
            }
            titleLower.contains("cosmos") || titleLower.contains("laundromat") -> {
                FallbackData(
                    poster = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=400",
                    backdrop = "https://images.unsplash.com/photo-1446776811953-b23d57bd21aa?q=80&w=1200",
                    overview = "Franck, a depressed sheep, meets a mysterious salesman named Victor who offers him a choice of different colorful parallel universes to escape his boring life.",
                    rating = "8.1",
                    releaseDate = "2015-08-24",
                    finalTitle = "Cosmos Laundromat"
                )
            }
            titleLower.contains("good omens") || titleLower.contains("omens") -> {
                FallbackData(
                    poster = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=400",
                    backdrop = "https://images.unsplash.com/photo-1483728642387-6c3bdd6c93e5?q=80&w=1200",
                    overview = "An angel and a demon who have grown quite fond of their comfortable lives on Earth must join forces to prevent the upcoming apocalypse and find the missing Antichrist.",
                    rating = "8.4",
                    releaseDate = "2019-05-31",
                    finalTitle = "Good Omens"
                )
            }
            else -> {
                // Procedural beautiful aesthetic fallback
                FallbackData(
                    poster = "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=400", // Cinematic camera
                    backdrop = "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=1200", // Theatre
                    overview = "A locally indexed high-definition media file parsed securely by Lumina Player scan algorithms.",
                    rating = "7.0",
                    releaseDate = parsed.year?.let { "$it-01-01" } ?: "2024-01-01",
                    finalTitle = parsed.cleanTitle
                )
            }
        }

        val epIdx = parsed.episode ?: 1
        val proceduralBackdrop = if (parsed.type == "SHOW_EPISODE") {
            CINEMATIC_STILLS[epIdx % CINEMATIC_STILLS.size]
        } else {
            fallback.backdrop
        }

        return MediaItem(
            id = id,
            filePath = filePath,
            fileName = fileName,
            title = if (parsed.type == "SHOW_EPISODE") "${fallback.finalTitle} - S${parsed.season}E${parsed.episode}" else fallback.finalTitle,
            type = parsed.type,
            showName = if (parsed.type == "SHOW_EPISODE") fallback.finalTitle else null,
            season = parsed.season,
            episodeNumber = parsed.episode,
            overview = fallback.overview,
            releaseDate = fallback.releaseDate,
            rating = fallback.rating.toFloatOrNull() ?: 7.0f,
            posterUrl = fallback.poster,
            backdropUrl = proceduralBackdrop,
            folderPath = folderPath
        )
    }

    suspend fun fetchFromOmdb(
        fileName: String,
        filePath: String,
        folderPath: String,
        omdbApiKey: String,
        id: String
    ): MediaItem? = withContext(Dispatchers.IO) {
        val parsed = parseFilename(fileName)
        val title = parsed.cleanTitle
        val year = parsed.year
        val type = parsed.type // "MOVIE" or "SHOW_EPISODE"
        
        val client = OkHttpClient()
        val searchUrl = "https://www.omdbapi.com/?apikey=${omdbApiKey}&s=${java.net.URLEncoder.encode(title, "UTF-8")}"
        val request = Request.Builder().url(searchUrl).build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val searchObj = JSONObject(bodyStr)
                if (searchObj.optString("Response") != "True") {
                    return@withContext queryDirectOmdb(title, year, type, omdbApiKey, filePath, fileName, folderPath, id)
                }
                
                val searchArr = searchObj.optJSONArray("Search") ?: return@withContext null
                if (searchArr.length() == 0) return@withContext null
                
                val candidates = mutableListOf<Pair<JSONObject, Double>>()
                val pathAndNameLower = "$filePath $fileName".lowercase()
                val isAnimeClue = pathAndNameLower.contains("anime") || pathAndNameLower.contains("gakkou") || 
                        pathAndNameLower.contains("japan") || pathAndNameLower.contains("nip") || pathAndNameLower.contains("dub") || 
                        pathAndNameLower.contains("sub") || pathAndNameLower.contains("ghost stories") ||
                        folderPath.lowercase().contains("anime")
                
                for (i in 0 until searchArr.length()) {
                    val cand = searchArr.getJSONObject(i)
                    val candTitle = cand.optString("Title", "")
                    val candYear = cand.optString("Year", "")
                    val candType = cand.optString("Type", "")
                    
                    var score = 0.0
                    if (type == "SHOW_EPISODE" && candType == "series") {
                        score += 50.0
                    } else if (type == "MOVIE" && candType == "movie") {
                        score += 50.0
                    }
                    
                    if (candTitle.equals(title, ignoreCase = true)) {
                        score += 100.0
                    } else if (candTitle.lowercase().contains(title.lowercase())) {
                        score += 30.0
                    }
                    
                    if (year != null) {
                        if (candYear.contains(year)) {
                            score += 80.0
                        }
                    }
                    candidates.add(Pair(cand, score))
                }
                
                candidates.sortByDescending { it.second }
                
                var bestMediaItem: MediaItem? = null
                var bestScore = -1.0
                
                for (k in 0 until minOf(candidates.size, 3)) {
                    val cand = candidates[k].first
                    val initialScore = candidates[k].second
                    val imdbId = cand.optString("imdbID", "")
                    if (imdbId.isBlank()) continue
                    
                    val detailUrl = "https://www.omdbapi.com/?apikey=${omdbApiKey}&i=$imdbId&plot=full"
                    val detailRequest = Request.Builder().url(detailUrl).build()
                    
                    try {
                        client.newCall(detailRequest).execute().use { dResponse ->
                            if (dResponse.isSuccessful) {
                                val dBody = dResponse.body?.string()
                                if (!dBody.isNullOrBlank()) {
                                    val dObj = JSONObject(dBody)
                                    if (dObj.optString("Response") == "True") {
                                        var finalScore = initialScore
                                        val genre = dObj.optString("Genre", "").lowercase()
                                        val country = dObj.optString("Country", "").lowercase()
                                        val plot = dObj.optString("Plot", "").lowercase()
                                        
                                        if (isAnimeClue) {
                                            if (genre.contains("animation") || genre.contains("anime")) {
                                                finalScore += 200.0
                                            }
                                            if (country.contains("japan")) {
                                                finalScore += 150.0
                                            }
                                            if (plot.contains("anime") || plot.contains("manga") || plot.contains("japanese")) {
                                                finalScore += 50.0
                                            }
                                        } else {
                                            if (genre.contains("animation") || genre.contains("anime")) {
                                                finalScore -= 30.0
                                            }
                                        }
                                        
                                        if (finalScore > bestScore) {
                                            bestScore = finalScore
                                            bestMediaItem = convertOmdbToMediaItem(
                                                dObj = dObj,
                                                parsed = parsed,
                                                filePath = filePath,
                                                fileName = fileName,
                                                folderPath = folderPath,
                                                id = id
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch OMDB details for $imdbId", e)
                    }
                }
                
                return@withContext bestMediaItem
            }
        } catch (e: Exception) {
            Log.e(TAG, "OMDB search error", e)
        }
        
        return@withContext queryDirectOmdb(title, year, type, omdbApiKey, filePath, fileName, folderPath, id)
    }

    private suspend fun queryDirectOmdb(
        title: String,
        year: String?,
        type: String,
        omdbApiKey: String,
        filePath: String,
        fileName: String,
        folderPath: String,
        id: String
    ): MediaItem? = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val omdbType = if (type == "SHOW_EPISODE") "series" else "movie"
        var url = "https://www.omdbapi.com/?apikey=${omdbApiKey}&t=${java.net.URLEncoder.encode(title, "UTF-8")}&type=$omdbType&plot=full"
        if (year != null) {
            url += "&y=$year"
        }
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val dObj = JSONObject(bodyStr)
                if (dObj.optString("Response") == "True") {
                    val parsed = parseFilename(fileName)
                    return@withContext convertOmdbToMediaItem(dObj, parsed, filePath, fileName, folderPath, id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct OMDB query failed", e)
        }
        return@withContext null
    }

    private fun convertOmdbToMediaItem(
        dObj: JSONObject,
        parsed: ParsedFilename,
        filePath: String,
        fileName: String,
        folderPath: String,
        id: String
    ): MediaItem {
        val oTitle = dObj.optString("Title", parsed.cleanTitle)
        val oType = dObj.optString("Type", "")
        val overview = dObj.optString("Plot", "No description available.")
        val releaseDate = dObj.optString("Released", dObj.optString("Year", "N/A"))
        
        val ratingStr = dObj.optString("imdbRating", "0.0")
        val rating = ratingStr.toFloatOrNull() ?: 0.0f
        
        val posterUrl = dObj.optString("Poster", "").takeIf { it.startsWith("http") && it != "N/A" }
            ?: "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?q=80&w=400"
        val backdropUrl = posterUrl
        
        val cast = dObj.optString("Actors", "").takeIf { it != "N/A" }
        val director = dObj.optString("Director", "").takeIf { it != "N/A" }

        val finalType = if (parsed.type == "SHOW_EPISODE" || oType == "series" || oType == "episode") "SHOW_EPISODE" else "MOVIE"

        return MediaItem(
            id = id,
            filePath = filePath,
            fileName = fileName,
            title = if (finalType == "SHOW_EPISODE") "$oTitle - S${parsed.season ?: 1}E${parsed.episode ?: 1}" else oTitle,
            type = finalType,
            showName = if (finalType == "SHOW_EPISODE") oTitle else null,
            season = if (finalType == "SHOW_EPISODE") (parsed.season ?: 1) else null,
            episodeNumber = if (finalType == "SHOW_EPISODE") (parsed.episode ?: 1) else null,
            overview = overview,
            releaseDate = releaseDate,
            rating = rating,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            folderPath = folderPath,
            cast = cast,
            director = director
        )
    }
}
