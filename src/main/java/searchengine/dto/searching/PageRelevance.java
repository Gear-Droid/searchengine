package searchengine.dto.searching;

import lombok.Data;

@Data
public class PageRelevance {

    private Integer pageId;  // id страницы

    private Double absoluteRelevance;  // абсолютная релевантность страницы в поиске

    private Double relativeRelevance;  // относительная релевантность страницы в поиске

}
