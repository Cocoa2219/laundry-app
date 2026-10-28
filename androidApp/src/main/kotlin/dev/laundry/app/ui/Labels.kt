package dev.laundry.app.ui

import androidx.annotation.StringRes
import dev.laundry.app.R
import dev.laundry.drying.Crowding
import dev.laundry.drying.Fabric
import dev.laundry.drying.Spot

@get:StringRes
internal val Fabric.labelRes: Int
    get() = when (this) {
        Fabric.THIN_TOP -> R.string.fabric_thin_top
        Fabric.TSHIRT -> R.string.fabric_tshirt
        Fabric.TOWEL -> R.string.fabric_towel
        Fabric.HOODIE -> R.string.fabric_hoodie
        Fabric.JEANS -> R.string.fabric_jeans
        Fabric.BEDDING -> R.string.fabric_bedding
    }

@get:StringRes
internal val Crowding.labelRes: Int
    get() = when (this) {
        Crowding.SPACED -> R.string.crowding_spaced
        Crowding.NORMAL -> R.string.crowding_normal
        Crowding.CROWDED -> R.string.crowding_crowded
    }

@get:StringRes
internal val Spot.labelRes: Int
    get() = when (this) {
        Spot.OPEN_YARD -> R.string.spot_open_yard
        Spot.BALCONY -> R.string.spot_balcony
        Spot.INDOOR -> R.string.spot_indoor
    }
