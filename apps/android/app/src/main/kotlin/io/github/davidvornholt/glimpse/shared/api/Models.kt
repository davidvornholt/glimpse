package io.github.davidvornholt.glimpse.shared.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant

/** The JSON settings every request and response share. */
val GlimpseJson =
    Json {
        ignoreUnknownKeys = true
        // Optional fields the app leaves at their default stay out of the body,
        // while a note set to null in a PATCH is sent as null.
        encodeDefaults = false
        explicitNulls = true
    }

/** ISO 8601 in UTC, as the API writes times. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

typealias Time =
    @Serializable(InstantSerializer::class)
    Instant

@Serializable
data class Device(
    val id: String,
    val name: String,
    val createdAt: Time,
    val lastSeenAt: Time? = null,
)

@Serializable
data class ClaimRequest(
    val code: String,
    val deviceName: String,
)

@Serializable
data class ClaimResult(
    val token: String,
    val device: Device,
)

@Serializable
data class Me(
    val kind: String,
    val device: Device? = null,
)

@Serializable
enum class PhotoSource {
    @SerialName("camera")
    Camera,

    @SerialName("scan")
    Scan,

    @SerialName("share")
    Share,
}

/** The image types the server keeps originals in, named by their MIME type. */
@Serializable
enum class OriginalType(
    val mimeType: String,
    val extension: String,
) {
    @SerialName("image/jpeg")
    Jpeg("image/jpeg", "jpg"),

    @SerialName("image/png")
    Png("image/png", "png"),

    @SerialName("image/webp")
    Webp("image/webp", "webp"),

    @SerialName("image/heic")
    Heic("image/heic", "heic"),

    @SerialName("image/heif")
    Heif("image/heif", "heif"),

    @SerialName("image/avif")
    Avif("image/avif", "avif"),
    ;

    companion object {
        fun fromMimeType(mimeType: String?): OriginalType? = entries.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }
    }
}

/** What `PUT /v1/photos/{id}` registers before the images follow. */
@Serializable
data class PhotoMetadata(
    val takenAt: Time,
    val source: PhotoSource,
    val width: Int,
    val height: Int,
    val originalType: OriginalType,
    val note: String? = null,
    val batchId: String? = null,
)

@Serializable
data class NotePatch(
    val note: String?,
)

@Serializable
data class Photo(
    val id: String,
    val createdAt: Time,
    val readyAt: Time? = null,
    val takenAt: Time,
    val expiresAt: Time,
    val source: PhotoSource,
    val note: String? = null,
    val width: Int,
    val height: Int,
    val originalType: OriginalType,
    val hasOriginal: Boolean,
    val originalBytes: Long? = null,
    val previewBytes: Long? = null,
    val deviceName: String? = null,
    val viewedAt: Time? = null,
    val batchId: String? = null,
)

@Serializable
data class PhotoPage(
    val photos: List<Photo>,
    val nextCursor: String? = null,
)

@Serializable
internal data class ErrorBody(
    val error: ErrorDetail,
)

@Serializable
internal data class ErrorDetail(
    val tag: String,
    val message: String,
)

@Serializable
internal data class DeletedEvent(
    val id: String,
)

@Serializable
internal data class WaitingEvent(
    val count: Int,
)

/** What `GET /v1/events` tells the phone. */
sealed interface ServerEvent {
    /** The stream is open; events from before it are not replayed. */
    data object Connected : ServerEvent

    /** A photo became ready, changed, or was first viewed by an agent. */
    data class PhotoChanged(
        val photo: Photo,
    ) : ServerEvent

    data class PhotoDeleted(
        val id: String,
    ) : ServerEvent

    /** How many agents are waiting for a photo right now. */
    data class Waiting(
        val count: Int,
    ) : ServerEvent
}
