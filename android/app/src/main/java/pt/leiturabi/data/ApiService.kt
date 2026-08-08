package pt.leiturabi.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface ApiService {

    @GET("health")
    suspend fun health(): HealthDto

    // ---------- criar registo ----------

    @Multipart
    @POST("api/extract")
    suspend fun extract(@Part file: MultipartBody.Part): ExtractResultDto

    @Multipart
    @POST("api/records")
    suspend fun createRecord(
        @Part files: List<MultipartBody.Part>,
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
    ): UploadResultDto

    @Multipart
    @POST("api/records/{id}/attachments")
    suspend fun addAttachments(
        @Path("id") id: Int,
        @Part files: List<MultipartBody.Part>,
    ): UploadResultDto

    @PATCH("api/records/{id}")
    suspend fun updateRecord(@Path("id") id: Int, @Body body: RecordUpdateDto): RecordDto

    @DELETE("api/records/{id}")
    suspend fun deleteRecord(@Path("id") id: Int): DeletedDto

    @DELETE("api/attachments/{id}")
    suspend fun deleteAttachment(@Path("id") id: Int): DeletedDto

    // ---------- pesquisar registo ----------

    @GET("api/records")
    suspend fun searchRecords(@QueryMap params: Map<String, String>): RecordPageDto

    @GET("api/records/{id}")
    suspend fun getRecord(@Path("id") id: Int): RecordDto

    @Multipart
    @POST("api/search/face")
    suspend fun searchByFace(@Part file: MultipartBody.Part): RecordPageDto

    @GET("api/filters")
    suspend fun filters(): FiltersDto

    // ---------- pessoas ----------

    @GET("api/persons")
    suspend fun listPersons(
        @Query("named_only") namedOnly: Boolean = false,
        @Query("min_faces") minFaces: Int = 1,
        @Query("limit") limit: Int = 300,
    ): List<PersonDto>

    @PATCH("api/persons/{id}")
    suspend fun updatePerson(@Path("id") id: Int, @Body body: PersonUpdateDto): PersonDto

    @POST("api/persons/{id}/merge/{other}")
    suspend fun mergePersons(@Path("id") id: Int, @Path("other") other: Int): PersonDto

    @POST("api/faces/{id}/assign")
    suspend fun assignFace(@Path("id") id: Int, @Body body: FaceAssignDto): FaceDto
}
