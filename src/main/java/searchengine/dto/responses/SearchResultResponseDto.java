package searchengine.dto.responses;

import lombok.Data;

import java.util.List;

@Data
public class SearchResultResponseDto {

    private boolean result;  // флаг результата поиска

    private int count;  // количество результатов

    private List<SearchResponseData> data;

    private String error;  // текст ошибки

}
