package com.github.serezhka.airplay.app.i18n;

import com.github.serezhka.airplay.app.settings.AppSettings;

import java.util.Locale;
import java.util.ResourceBundle;

public final class I18n {

    private ResourceBundle bundle;

    public I18n(AppSettings.LanguageMode mode) {
        setLanguage(mode);
    }

    public void setLanguage(AppSettings.LanguageMode mode) {
        Locale locale = switch (mode) {
            case ZH_CN -> Locale.SIMPLIFIED_CHINESE;
            case EN -> Locale.ENGLISH;
            case SYSTEM -> Locale.getDefault();
        };
        bundle = ResourceBundle.getBundle("i18n.messages", locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
    }

    public String text(String key, Object... arguments) {
        return arguments.length == 0
                ? bundle.getString(key)
                : java.text.MessageFormat.format(bundle.getString(key), arguments);
    }
}
