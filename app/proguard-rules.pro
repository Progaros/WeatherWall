-keep class info.olli.weatherlwp.wallpaper.WeatherWallpaper { *; }
-keep class info.olli.weatherlwp.MainActivity { *; }
-keep class info.olli.weatherlwp.weather.BreezyWeatherReceiver { *; }
-keep public class * extends android.view.View { public <init>(...); }
-keep public class * extends androidx.preference.Preference { public <init>(...); }
# Sub-screens are instantiated by class name from app:fragment attributes in res/xml.
-keep public class * extends androidx.fragment.app.Fragment { public <init>(); }
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
