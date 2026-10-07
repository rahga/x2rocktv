package com.rahga.x2rock.model

enum class AppColorTheme(val displayName: String) {
    DEFAULT("Default"),
    OCEAN("Ocean"),
    EMBER("Ember"),
    FOREST("Forest"),
    ORCHID("Orchid"),

    /** The accent follows the selected room's cover art, as the Sonos app's Now Playing does. */
    ARTWORK("Artwork"),
}
