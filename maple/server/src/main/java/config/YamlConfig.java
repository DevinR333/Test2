package config;

import com.esotericsoftware.yamlbeans.YamlReader;
import constants.string.CharsetConstants;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


public class YamlConfig {
    public static final String CONFIG_FILE_NAME = "config.yaml";
    public static final YamlConfig config = loadConfig();

    public List<WorldConfig> worlds;
    public ServerConfig server;

    private static YamlConfig loadConfig() {
        try {
            java.io.Reader source;
            if (Files.exists(java.nio.file.Paths.get(CONFIG_FILE_NAME))) {
                source = Files.newBufferedReader(java.nio.file.Paths.get(CONFIG_FILE_NAME), CharsetConstants.CHARSET);
            } else {
                // Offline build: the config ships inside the app.
                java.io.InputStream in = YamlConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE_NAME);
                if (in == null) throw new FileNotFoundException(CONFIG_FILE_NAME);
                source = new java.io.InputStreamReader(in, CharsetConstants.CHARSET);
            }
            YamlReader reader = new YamlReader(source);
            YamlConfig config = reader.read(YamlConfig.class);
            reader.close();
            return config;
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Could not read config file " + YamlConfig.CONFIG_FILE_NAME + ": " + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException("Could not successfully parse config file " + YamlConfig.CONFIG_FILE_NAME + ": " + e.getMessage());
        }
    }
}
