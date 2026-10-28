package dev.laundry.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.laundry.drying.Crowding
import dev.laundry.drying.Fabric
import dev.laundry.drying.Load
import dev.laundry.drying.Spot
import kotlinx.coroutines.flow.first

/** Remembers the last load picked, so a repeat check is a single tap. */
class LoadPreferences(private val store: DataStore<Preferences>) {

    suspend fun get(): Load {
        val prefs = store.data.first()
        return Load(
            fabric = prefs[FABRIC].toEnumOr(DEFAULT.fabric),
            crowding = prefs[CROWDING].toEnumOr(DEFAULT.crowding),
            spot = prefs[SPOT].toEnumOr(DEFAULT.spot),
        )
    }

    suspend fun save(load: Load) {
        store.edit {
            it[FABRIC] = load.fabric.name
            it[CROWDING] = load.crowding.name
            it[SPOT] = load.spot.name
        }
    }

    companion object {
        val DEFAULT = Load(Fabric.TOWEL, Crowding.NORMAL, Spot.OPEN_YARD)

        private val FABRIC = stringPreferencesKey("load_fabric")
        private val CROWDING = stringPreferencesKey("load_crowding")
        private val SPOT = stringPreferencesKey("load_spot")
    }
}

// An unknown name (e.g. an enum constant removed in a later version) falls back to the default.
private inline fun <reified T : Enum<T>> String?.toEnumOr(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
