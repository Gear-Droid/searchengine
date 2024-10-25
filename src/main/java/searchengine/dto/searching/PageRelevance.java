package searchengine.dto.searching;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter @Setter
public class PageRelevance {

    public PageRelevance(Object rawObject) {
        Map<String, Number> valuesMap = (Map<String, Number>) rawObject;
        for (Map.Entry<String, Number> valueEntry : valuesMap.entrySet()) {
            Object value = valueEntry.getValue();
            switch (valueEntry.getKey()) {
                case "page_id" -> pageId = (Integer) value;
                case "absolute_relevance" -> absoluteRelevance = (Double) value;
                case "relative_relevance" -> relativeRelevance = (Double) value;
                default -> throw new RuntimeException();
            }
        }
    }

    Integer pageId;  // id страницы

    Double absoluteRelevance;  // абсолютная релевантность страницы в поиске

    Double relativeRelevance;  // относительная релевантность страницы в поиске

}
