package searchengine.dto.responses;

import lombok.Getter;

@Getter
public class ErrorResponseDto extends ResponseDto {

    private final String error;

    public ErrorResponseDto(boolean result, String error) {
        this.result = result;
        this.error = error;
    }

}
