package searchengine.services.indexing;

public class IndexingAlreadyLaunchedException extends RuntimeException {

    public IndexingAlreadyLaunchedException(String message) {
        super(message);
    }

}
