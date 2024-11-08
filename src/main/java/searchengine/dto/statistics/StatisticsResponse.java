package searchengine.dto.statistics;

import lombok.AllArgsConstructor;
import lombok.Getter;
import searchengine.dto.responses.ResponseDto;

@Getter
@AllArgsConstructor
public class StatisticsResponse extends ResponseDto {

    private boolean result;

    private StatisticsData statistics;

}
