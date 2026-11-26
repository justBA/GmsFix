package com.anb.gmsfix;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        I18n.init(this);
    }
}
