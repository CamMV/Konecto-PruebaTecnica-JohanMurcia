package com.konecto.series76.data.remote

import com.konecto.series76.data.remote.dto.ConversationRequest
import com.konecto.series76.data.remote.dto.ConversationResponse
import com.konecto.series76.data.remote.dto.DebugSessionListDto
import com.konecto.series76.data.remote.dto.DebugTraceDto
import com.konecto.series76.data.remote.dto.HealthDto
import com.konecto.series76.data.remote.dto.LoginRequest
import com.konecto.series76.data.remote.dto.LoginResponse
import com.konecto.series76.data.remote.dto.SessionListDto
import com.konecto.series76.data.remote.dto.SessionMessagesDto
import com.konecto.series76.data.remote.dto.UserDto
import com.konecto.series76.data.remote.interceptor.BaseUrlInterceptor
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Backend endpoints. Paths are relative ("api/…"): the host comes from [BaseUrlInterceptor], so the backend URL can
 * change at runtime without rebuilding Retrofit.
 */
interface KonectoApi {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/auth/me")
    suspend fun me(): UserDto

    @POST("api/conversation")
    suspend fun converse(@Body body: ConversationRequest): ConversationResponse

    @GET("api/sessions")
    suspend fun sessions(@Query("limit") limit: Int = 100, @Query("offset") offset: Int = 0): SessionListDto

    @GET("api/sessions/{id}/messages")
    suspend fun messages(@Path("id") sessionId: String): SessionMessagesDto

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(@Path("id") sessionId: String): Response<Unit>

    @GET("api/debug/sessions")
    suspend fun debugSessions(@Query("limit") limit: Int = 500, @Query("offset") offset: Int = 0): DebugSessionListDto

    @GET("api/debug/sessions/{id}/messages")
    suspend fun debugMessages(@Path("id") sessionId: String): SessionMessagesDto

    @GET("api/messages/{id}/debug")
    suspend fun debugTrace(@Path("id") messageId: String): DebugTraceDto

    /** Health of an arbitrary server (absolute URL), used to test a URL before saving it. */
    @Headers("${BaseUrlInterceptor.ABSOLUTE_URL_HEADER}: true")
    @GET
    suspend fun healthAt(@Url absoluteUrl: String): HealthDto
}
