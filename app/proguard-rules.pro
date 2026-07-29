# OkHttp ships these rules itself, but it references optional Conscrypt/BouncyCastle
# providers that are not on the classpath here. Silence the warnings rather than
# pulling in providers the app has no use for.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
