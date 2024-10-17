package searchengine.services.morphology.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class DefaultAdvice {
    @ExceptionHandler(WordNotFitToDictionaryException.class)
    public ResponseEntity<ErrorResponse> handleException(WordNotFitToDictionaryException e) {
        ErrorResponse response = ErrorResponse.create(e, HttpStatus.BAD_REQUEST, e.getMessage());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
}
