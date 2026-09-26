package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonClassDiscriminator
import java.util.UUID

const val HOME_SIDE_PANEL_LAYOUT_VERSION = 1
const val HOME_SIDE_PANEL_IMAGE_MIN_HEIGHT_DP = 80
const val HOME_SIDE_PANEL_IMAGE_MAX_HEIGHT_DP = 800
const val HOME_SIDE_PANEL_IMAGE_HEIGHT_STEP_DP = 8
const val HOME_SIDE_PANEL_IMAGE_MAX_ASPECT_RATIO = 100
const val HOME_SIDE_PANEL_BACKGROUND_ALPHA_MIN = 0
const val HOME_SIDE_PANEL_BACKGROUND_ALPHA_MAX = 100

/**
 * Opacity of a freshly imported card background. Existing layouts decode with this default too,
 * but they carry no asset id, so nothing is painted until the user picks an image.
 */
const val HOME_SIDE_PANEL_BACKGROUND_ALPHA_DEFAULT = 100

fun interface HomeSidePanelIdGenerator {
    fun nextId(): String
}

object UuidHomeSidePanelIdGenerator : HomeSidePanelIdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}

@Serializable
data class HomeSidePanelLayout(
    val version: Int = HOME_SIDE_PANEL_LAYOUT_VERSION,
    val cards: List<HomeSidePanelCardConfig>,
)

@Serializable
enum class HomeSidePanelActionKind {
    ADD_FRIEND,
    SCAN,
    MOMENTS,
    WALLET,
    CHANNELS,
    WECHAT_SETTINGS,
    FAVORITES,
    WEKIT_SETTINGS,
    RESTART_WECHAT,
    FORCE_STOP_WECHAT,
    MARK_ALL_READ,
}

@Serializable
enum class HomeSidePanelCardType {
    DATE_TIME,
    WEATHER,
    WALLET,
    HITOKOTO,
    IMAGE,
    MUSIC,
    CALENDAR,
    HORIZONTAL_ACTIONS,
    VERTICAL_ACTIONS,
}

@Serializable
data class HomeSidePanelActionConfig(
    val id: String,
    val kind: HomeSidePanelActionKind,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("cardType")
sealed class HomeSidePanelCardConfig {
    abstract val id: String
    abstract val type: HomeSidePanelCardType
}

/**
 * Cards that can paint a user supplied image underneath their own content.
 *
 * [backgroundImageAssetId] is an asset id owned by [HomeSidePanelImageAssetStore] inside the
 * module private directory, never an external `content://` Uri, so rendering never depends on a
 * still valid read grant. [backgroundImageAlpha] is the opacity in percent:
 * [HOME_SIDE_PANEL_BACKGROUND_ALPHA_MIN] means "no background", and a card without an asset id
 * ignores it entirely.
 */
interface HomeSidePanelBackgroundImageCardConfig {
    val id: String
    val backgroundImageAssetId: String?
    val backgroundImageAlpha: Int
}

/**
 * Returns a copy of this card with its background image replaced. [alphaPercent] is clamped into
 * the supported range. Only the five container cards implement
 * [HomeSidePanelBackgroundImageCardConfig], so the `else` branch is unreachable in practice.
 */
internal fun HomeSidePanelBackgroundImageCardConfig.withCardBackground(
    assetId: String?,
    alphaPercent: Int,
): HomeSidePanelCardConfig {
    val alpha = alphaPercent.coerceIn(
        HOME_SIDE_PANEL_BACKGROUND_ALPHA_MIN,
        HOME_SIDE_PANEL_BACKGROUND_ALPHA_MAX,
    )
    return when (this) {
        is DateTimeCardConfig -> copy(backgroundImageAssetId = assetId, backgroundImageAlpha = alpha)
        is CalendarCardConfig -> copy(backgroundImageAssetId = assetId, backgroundImageAlpha = alpha)
        is WeatherCardConfig -> copy(backgroundImageAssetId = assetId, backgroundImageAlpha = alpha)
        is WalletCardConfig -> copy(backgroundImageAssetId = assetId, backgroundImageAlpha = alpha)
        is HitokotoCardConfig -> copy(backgroundImageAssetId = assetId, backgroundImageAlpha = alpha)
        else -> error("Card '$id' cannot hold a background image")
    }
}

internal fun HomeSidePanelCardConfig.backgroundImageAssetIdOrNull(): String? =
    (this as? HomeSidePanelBackgroundImageCardConfig)
        ?.backgroundImageAssetId
        ?.takeIf { it.isNotBlank() }

internal fun HomeSidePanelCardConfig.supportsBackgroundImage(): Boolean =
    this is HomeSidePanelBackgroundImageCardConfig

@Serializable
@SerialName("date_time")
data class DateTimeCardConfig(
    override val id: String,
    val showLunarCalendar: Boolean = false,
    override val backgroundImageAssetId: String? = null,
    override val backgroundImageAlpha: Int = HOME_SIDE_PANEL_BACKGROUND_ALPHA_DEFAULT,
) : HomeSidePanelCardConfig(), HomeSidePanelBackgroundImageCardConfig {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.DATE_TIME
}

@Serializable
@SerialName("weather")
data class WeatherCardConfig(
    override val id: String,
    val city: WeatherCity,
    override val backgroundImageAssetId: String? = null,
    override val backgroundImageAlpha: Int = HOME_SIDE_PANEL_BACKGROUND_ALPHA_DEFAULT,
) : HomeSidePanelCardConfig(), HomeSidePanelBackgroundImageCardConfig {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.WEATHER
}

@Serializable
@SerialName("wallet")
data class WalletCardConfig(
    override val id: String,
    val hideBalanceByDefault: Boolean = false,
    override val backgroundImageAssetId: String? = null,
    override val backgroundImageAlpha: Int = HOME_SIDE_PANEL_BACKGROUND_ALPHA_DEFAULT,
) : HomeSidePanelCardConfig(), HomeSidePanelBackgroundImageCardConfig {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.WALLET
}

@Serializable
@SerialName("hitokoto")
data class HitokotoCardConfig(
    override val id: String,
    val settings: HitokotoSettings = HitokotoSettings(),
    override val backgroundImageAssetId: String? = null,
    override val backgroundImageAlpha: Int = HOME_SIDE_PANEL_BACKGROUND_ALPHA_DEFAULT,
) : HomeSidePanelCardConfig(), HomeSidePanelBackgroundImageCardConfig {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.HITOKOTO
}

@Serializable
enum class HomeSidePanelImageScaleMode {
    CROP,
    FIT,
    FILL_BOUNDS,
    AUTO_RATIO,
}

@Serializable
@SerialName("image")
data class ImageCardConfig(
    override val id: String,
    val imageAssetId: String? = null,
    val imageWidthPx: Int? = null,
    val imageHeightPx: Int? = null,
    val heightDp: Int = 240,
    val scaleMode: HomeSidePanelImageScaleMode = HomeSidePanelImageScaleMode.CROP,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.IMAGE
}

@Serializable
@SerialName("horizontal_actions")
data class HorizontalActionsCardConfig(
    override val id: String,
    val actions: List<HomeSidePanelActionConfig>,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.HORIZONTAL_ACTIONS
}

@Serializable
@SerialName("vertical_actions")
data class VerticalActionsCardConfig(
    override val id: String,
    val actions: List<HomeSidePanelActionConfig>,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.VERTICAL_ACTIONS
}

@Serializable
@SerialName("music")
data class MusicCardConfig(
    override val id: String,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.MUSIC
}

@Serializable
@SerialName("calendar")
data class CalendarCardConfig(
    override val id: String,
    val showLunarCalendar: Boolean = true,
    override val backgroundImageAssetId: String? = null,
    override val backgroundImageAlpha: Int = HOME_SIDE_PANEL_BACKGROUND_ALPHA_DEFAULT,
) : HomeSidePanelCardConfig(), HomeSidePanelBackgroundImageCardConfig {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.CALENDAR
}

class InvalidHomeSidePanelLayoutException(message: String) : IllegalArgumentException(message)

fun validateHomeSidePanelLayout(layout: HomeSidePanelLayout) {
    if (layout.version != HOME_SIDE_PANEL_LAYOUT_VERSION) {
        throw InvalidHomeSidePanelLayoutException("Unsupported layout version: ${layout.version}")
    }
    val cardIds = layout.cards.map(HomeSidePanelCardConfig::id)
    if (cardIds.any(String::isBlank)) {
        throw InvalidHomeSidePanelLayoutException("Card IDs must not be blank")
    }
    if (cardIds.size != cardIds.toSet().size) {
        throw InvalidHomeSidePanelLayoutException("Card IDs must be unique")
    }
    layout.cards.forEach { card ->
        validateCardBackground(card)
        when (card) {
            is HitokotoCardConfig -> validateHitokotoSettings(
                minLength = card.settings.minLength,
                maxLength = card.settings.maxLength,
                categories = card.settings.categories,
            )?.let { throw InvalidHomeSidePanelLayoutException("Invalid hitokoto settings: $it") }

            is ImageCardConfig -> {
                card.imageAssetId?.let(::validateImageAssetId)
                if (
                    card.heightDp !in HOME_SIDE_PANEL_IMAGE_MIN_HEIGHT_DP..HOME_SIDE_PANEL_IMAGE_MAX_HEIGHT_DP ||
                    card.heightDp % HOME_SIDE_PANEL_IMAGE_HEIGHT_STEP_DP != 0
                ) {
                    throw InvalidHomeSidePanelLayoutException("Invalid image card height: ${card.heightDp}")
                }
                val width = card.imageWidthPx
                val height = card.imageHeightPx
                if (card.imageAssetId == null && (width != null || height != null)) {
                    throw InvalidHomeSidePanelLayoutException("An empty image card cannot have dimensions")
                }
                if ((width == null) != (height == null)) {
                    throw InvalidHomeSidePanelLayoutException("Image dimensions must both be present or absent")
                }
                if (width != null && height != null) {
                    if (
                        width <= 0 ||
                        height <= 0 ||
                        width.toLong() * height.toLong() > 50_000_000L ||
                        !isHomeSidePanelImageAspectRatioSupported(width, height)
                    ) {
                        throw InvalidHomeSidePanelLayoutException("Invalid image dimensions: ${width}x$height")
                    }
                }
            }

            is HorizontalActionsCardConfig -> validateActionIds(card.actions)
            is VerticalActionsCardConfig -> validateActionIds(card.actions)
            else -> Unit
        }
    }
}

fun isHomeSidePanelImageAspectRatioSupported(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val longer = maxOf(width, height).toLong()
    val shorter = minOf(width, height).toLong()
    return longer <= shorter * HOME_SIDE_PANEL_IMAGE_MAX_ASPECT_RATIO
}

fun HomeSidePanelLayout.imageAssetIds(): Set<String> = cards
    .mapNotNullTo(linkedSetOf()) { card ->
        // Card background images live in the same asset store as image cards, so they have to be
        // reported here: this set is what gets promoted from the draft on save and what protects
        // already saved assets from being garbage collected.
        if (card is ImageCardConfig) card.imageAssetId else card.backgroundImageAssetIdOrNull()
    }

private fun validateCardBackground(card: HomeSidePanelCardConfig) {
    val background = card as? HomeSidePanelBackgroundImageCardConfig ?: return
    if (
        background.backgroundImageAlpha < HOME_SIDE_PANEL_BACKGROUND_ALPHA_MIN ||
        background.backgroundImageAlpha > HOME_SIDE_PANEL_BACKGROUND_ALPHA_MAX
    ) {
        throw InvalidHomeSidePanelLayoutException(
            "Invalid card background alpha: ${background.backgroundImageAlpha}",
        )
    }
    background.backgroundImageAssetId?.let(::validateImageAssetId)
}

private fun validateImageAssetId(assetId: String) {
    val parsed = runCatching { UUID.fromString(assetId) }.getOrElse {
        throw InvalidHomeSidePanelLayoutException("Invalid image asset ID: $assetId")
    }
    if (parsed.toString() != assetId) {
        throw InvalidHomeSidePanelLayoutException("Invalid image asset ID: $assetId")
    }
}

private fun validateActionIds(actions: List<HomeSidePanelActionConfig>) {
    val actionIds = actions.map(HomeSidePanelActionConfig::id)
    if (actionIds.any(String::isBlank)) {
        throw InvalidHomeSidePanelLayoutException("Action IDs must not be blank")
    }
    if (actionIds.size != actionIds.toSet().size) {
        throw InvalidHomeSidePanelLayoutException("Action IDs must be unique within a card")
    }
}

object HomeSidePanelLayoutCodec {

    fun encode(layout: HomeSidePanelLayout): String {
        validateHomeSidePanelLayout(layout)
        return DefaultJson.encodeToString(layout)
    }

    fun decode(raw: String): HomeSidePanelLayout =
        DefaultJson.decodeFromString<HomeSidePanelLayout>(raw).also(::validateHomeSidePanelLayout)

    fun load(
        raw: String,
        legacy: LegacyHomeSidePanelSnapshot,
        idGenerator: HomeSidePanelIdGenerator,
    ): HomeSidePanelLayoutLoad = try {
        HomeSidePanelLayoutLoad.Stored(decode(raw))
    } catch (error: Exception) {
        HomeSidePanelLayoutLoad.Fallback(
            layout = defaultHomeSidePanelLayout(legacy, idGenerator),
            invalidRaw = raw,
            reason = error.message ?: error::class.simpleName.orEmpty(),
        )
    }
}

data class LegacyHomeSidePanelSnapshot(
    val weatherCity: WeatherCity,
    val hideWalletBalance: Boolean,
    val hitokotoSettings: HitokotoSettings,
) {
    companion object {
        fun defaults() = LegacyHomeSidePanelSnapshot(
            DEFAULT_WEATHER_CITY,
            false,
            HitokotoSettings(),
        )
    }
}

sealed interface HomeSidePanelLayoutLoad {
    val layout: HomeSidePanelLayout

    data class Stored(override val layout: HomeSidePanelLayout) : HomeSidePanelLayoutLoad
    data class Migrated(override val layout: HomeSidePanelLayout) : HomeSidePanelLayoutLoad
    data class Fallback(
        override val layout: HomeSidePanelLayout,
        val invalidRaw: String,
        val reason: String,
    ) : HomeSidePanelLayoutLoad
}

fun defaultHomeSidePanelLayout(
    legacy: LegacyHomeSidePanelSnapshot,
    idGenerator: HomeSidePanelIdGenerator = UuidHomeSidePanelIdGenerator,
): HomeSidePanelLayout = HomeSidePanelLayout(
    cards = listOf(
        DateTimeCardConfig(idGenerator.nextId()),
        CalendarCardConfig(idGenerator.nextId()),
        WeatherCardConfig(idGenerator.nextId(), legacy.weatherCity),
        WalletCardConfig(idGenerator.nextId(), legacy.hideWalletBalance),
        VerticalActionsCardConfig(
            idGenerator.nextId(),
            listOf(
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.ADD_FRIEND),
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.MOMENTS),
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.CHANNELS),
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.MARK_ALL_READ),
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.WEKIT_SETTINGS),
            ),
        ),
        HitokotoCardConfig(idGenerator.nextId(), legacy.hitokotoSettings),
        MusicCardConfig(idGenerator.nextId()),
    ),
)
