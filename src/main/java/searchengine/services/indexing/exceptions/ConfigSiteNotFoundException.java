package searchengine.services.indexing.exceptions;

public class ConfigSiteNotFoundException extends RuntimeException {

    public ConfigSiteNotFoundException(String message) {
        super(message);
    }
}