package failchat

import mu.KotlinLogging
import org.apache.commons.configuration2.CompositeConfiguration
import org.apache.commons.configuration2.Configuration
import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.sync.ReadWriteSynchronizer
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path


/**
 * Loads and saves the configuration.
 * */
class ConfigLoader(private val configDirectory: Path) {

    private companion object {
        val logger = KotlinLogging.logger {}
    }

    private val userConfigPath = configDirectory.resolve("user.properties")
    private val defaultConfig = createMandatoryConfig("/config/default.properties")
    // private.properties holds the (optional) twitch credentials. It may be absent or empty
    // in builds made without secrets, so a missing file must not break the application.
    private val privateConfig = createOptionalResourceConfig("/config/private.properties")

    @Volatile
    private var loadedConfig: LoadedConfig? = null

    fun load(): Configuration {
        loadedConfig?.let { return it.compositeConfig }

        val userConfigBuilder = createOptionalConfig(userConfigPath)
        val userConfig = userConfigBuilder.configuration

        // If passed to the CompositeConfiguration constructor as inMemoryConfig,
        // it would be the last one in the lookup order
        val compositeConfig = CompositeConfiguration()

        compositeConfig.addConfiguration(userConfig, true)
        compositeConfig.addConfiguration(defaultConfig)
        compositeConfig.addConfiguration(privateConfig)
        compositeConfig.synchronizer = ReadWriteSynchronizer()
        compositeConfig.isThrowExceptionOnMissing = true

        loadedConfig = LoadedConfig(userConfigBuilder, compositeConfig)

        return compositeConfig
    }

    fun dropLoadedConfig() {
        loadedConfig = null
        logger.info("Loaded config was dropped")
    }

    fun save() {
        Files.createDirectories(configDirectory)
        val config = loadedConfig ?: run {
            logger.warn("There is not last loaded config to save")
            return
        }

        config.userConfigBuilder.save()
        logger.info("User config saved to '{}'", userConfigPath)
    }

    fun deleteUserConfigFile() {
        Files.deleteIfExists(userConfigPath)
        logger.info("User configuration file was deleted, path: {}", userConfigPath)
    }

    private fun createOptionalConfig(path: Path): FileBasedConfigurationBuilder<PropertiesConfiguration> {
        // last argument (true) - do not throw exception if config not exists, just get empty config
        return FileBasedConfigurationBuilder(PropertiesConfiguration::class.java, null, true)
                .configure(
                        Parameters()
                                .properties()
                                .setPath(path.toAbsolutePath().toString())
                                .setThrowExceptionOnMissing(true)
                )
    }

    private fun createMandatoryConfig(resource: String): PropertiesConfiguration {
        val url = javaClass.getResource(resource)
                ?: throw IllegalStateException("Mandatory configuration resource was not found: $resource")
        return readResourceConfig(url)
    }

    private fun createOptionalResourceConfig(resource: String): PropertiesConfiguration {
        val url = javaClass.getResource(resource)
        if (url == null) {
            logger.warn("Configuration resource '{}' was not found, using empty configuration", resource)
            return PropertiesConfiguration().also { it.isThrowExceptionOnMissing = true }
        }
        return readResourceConfig(url)
    }

    /**
     * Reads a bundled properties file ignoring an UTF-8 byte order mark.
     *
     * A BOM at the beginning of the file (e.g. written by Windows PowerShell's "Set-Content -Encoding utf8"
     * during a build) otherwise becomes a part of the first key, so that key is silently "lost".
     * Encoding is ISO-8859-1, the same as the default one for [PropertiesConfiguration].
     */
    private fun readResourceConfig(url: URL): PropertiesConfiguration {
        val bytes = url.openStream().use { it.readBytes() }
        val hasBom = bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
        val offset = if (hasBom) 3 else 0
        if (hasBom) {
            logger.warn("Byte order mark was found and ignored in configuration resource '{}'", url)
        }

        val config = PropertiesConfiguration()
        config.isThrowExceptionOnMissing = true
        InputStreamReader(ByteArrayInputStream(bytes, offset, bytes.size - offset), StandardCharsets.ISO_8859_1).use {
            config.read(it)
        }
        return config
    }

    private class LoadedConfig(
            val userConfigBuilder: FileBasedConfigurationBuilder<PropertiesConfiguration>,
            val compositeConfig: CompositeConfiguration
    )
}
