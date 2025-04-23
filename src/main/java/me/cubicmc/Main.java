package me.cubicmc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    
    public static void main(String[] args) throws IOException, InterruptedException {
            Launcher.launch(
                    "1.21.5",
                    "xd",
                    "melasrosca",
                    "C:/Program Files/Java/jdk-23/bin/java.exe",
                    "2G",
                    "6G",
                    442,
                    842,
                    true,
                    Launcher.ModLoader.FABRIC,
                    "0.16.13"
            );
    }
}