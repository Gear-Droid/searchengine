package searchengine.dto.statistics;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class StatisticsData {

    private TotalStatistics total;

    private List<DetailedStatisticsItem> detailed;

}
