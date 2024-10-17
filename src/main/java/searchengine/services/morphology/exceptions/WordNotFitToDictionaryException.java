package searchengine.services.morphology.exceptions;

public class WordNotFitToDictionaryException extends RuntimeException {
    public WordNotFitToDictionaryException(String word) {
        super("Word <" + word + "> not contains in dictionary!");
    }
}
