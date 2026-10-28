package dev.laundry.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.laundry.drying.Crowding
import dev.laundry.drying.Fabric
import dev.laundry.drying.Load
import dev.laundry.drying.Spot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LoadPreferencesTest {

    @Test
    fun nothingSaved_returnsDefault() = runTest {
        assertEquals(LoadPreferences.DEFAULT, LoadPreferences(InMemoryPreferencesStore()).get())
    }

    @Test
    fun save_thenGet_roundTrips() = runTest {
        val prefs = LoadPreferences(InMemoryPreferencesStore())
        val load = Load(Fabric.JEANS, Crowding.CROWDED, Spot.BALCONY)

        prefs.save(load)

        assertEquals(load, prefs.get())
    }

    @Test
    fun unknownStoredName_fallsBackToDefaultForThatField() = runTest {
        val store = InMemoryPreferencesStore()
        store.edit {
            it[stringPreferencesKey("load_fabric")] = "SWEATER_FROM_A_FUTURE_VERSION"
            it[stringPreferencesKey("load_spot")] = "BALCONY"
        }

        val load = LoadPreferences(store).get()

        assertEquals(Fabric.TOWEL, load.fabric)
        assertEquals(Spot.BALCONY, load.spot)
    }
}
