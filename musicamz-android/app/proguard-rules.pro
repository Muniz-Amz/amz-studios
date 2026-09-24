# Regras específicas do MusicAmz.
# Media3, Room e Compose já fornecem as regras de consumo necessárias.
# Este arquivo existe para manter a compilação release reproduzível.

# Commons Compress instantiates ZIP metadata handlers with Class.newInstance().
# Keep concrete classes and their constructors for first-run runtime extraction.
-keep,allowobfuscation class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
