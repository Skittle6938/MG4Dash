package com.saicmotor.common;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Copy of the command the SAIC launcher sends to the services of media and radio apps
 * ("command" extra of a startService). The class name and Parcel order must be
 * identical to the firmware's: the receiving app rereads it with its own class.
 * Found on SWI69 (launcher com.saicmotor.launcher, ModeManager.switchSource).
 */
public class CommandParcelable implements Parcelable {
    private int mEvent = -1;
    private String mPackage = "";
    private int mTargetType = -1;
    private int mTargetState = -1;
    private Bundle mCommandParam = new Bundle();

    public CommandParcelable() {}

    protected CommandParcelable(Parcel in) {
        mEvent = in.readInt();
        mPackage = in.readString();
        mTargetType = in.readInt();
        mTargetState = in.readInt();
        mCommandParam = in.readBundle(getClass().getClassLoader());
    }

    public CommandParcelable setEvent(int event) { mEvent = event; return this; }
    public CommandParcelable setTargetPackage(String pkg) { mPackage = pkg; return this; }
    public CommandParcelable setTargetType(int type) { mTargetType = type; return this; }
    public CommandParcelable setTargetState(int state) { mTargetState = state; return this; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEvent);
        dest.writeString(mPackage);
        dest.writeInt(mTargetType);
        dest.writeInt(mTargetState);
        dest.writeBundle(mCommandParam);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<CommandParcelable> CREATOR = new Creator<CommandParcelable>() {
        @Override public CommandParcelable createFromParcel(Parcel in) { return new CommandParcelable(in); }
        @Override public CommandParcelable[] newArray(int size) { return new CommandParcelable[size]; }
    };

    @Override
    public String toString() {
        return "CommandParcelable{mEvent=" + mEvent + ", mPackage='" + mPackage + "', mTargetType=" + mTargetType + ", mTargetState=" + mTargetState + "}";
    }
}
