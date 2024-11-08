package searchengine.exceptions;

public class IndexingAlreadyLaunchedException extends RuntimeException {

    public IndexingAlreadyLaunchedException(String message) {
        super(message);
    }

}
