package com.pocketrealm.pkg

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable projection of the realm shared-object probe, for crossing the
 * :pkg -> :main process boundary via AIDL (IPkgIsolation.loadRealmSoBySoname).
 */
data class RealmSoInfoParcelable(
    val loaded: Int,
    val err: Int,
    val path: String,
    val soname: String,
    val symbol: String,
    val baseAddr: Long,
) : Parcelable {

    constructor(p: PkgNative.RealmSoInfo) : this(
        loaded = p.loaded, err = p.err, path = p.path, soname = p.soname,
        symbol = p.symbol, baseAddr = p.baseAddr,
    )

    fun toInfo(): PkgNative.RealmSoInfo =
        PkgNative.RealmSoInfo(loaded, err, path, soname, symbol, baseAddr)

    val isLoaded: Boolean get() = loaded == 1

    override fun describeContents(): Int = 0
    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeInt(loaded)
        out.writeInt(err)
        out.writeString(path)
        out.writeString(soname)
        out.writeString(symbol)
        out.writeLong(baseAddr)
    }

    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<RealmSoInfoParcelable> {
            override fun createFromParcel(`in`: Parcel): RealmSoInfoParcelable =
                RealmSoInfoParcelable(
                    loaded = `in`.readInt(),
                    err = `in`.readInt(),
                    path = `in`.readString().orEmpty(),
                    soname = `in`.readString().orEmpty(),
                    symbol = `in`.readString().orEmpty(),
                    baseAddr = `in`.readLong(),
                )
            override fun newArray(size: Int): Array<RealmSoInfoParcelable?> = arrayOfNulls(size)
        }
    }
}
