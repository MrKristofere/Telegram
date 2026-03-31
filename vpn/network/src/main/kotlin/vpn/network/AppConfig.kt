package vpn.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("api_base_url")
    val baseUrl: String,
    @SerialName("anonymous_key_relative_path")
    val anonymousKeyPath: String,
)
