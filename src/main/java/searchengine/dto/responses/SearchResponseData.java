package searchengine.dto.responses;

import lombok.Data;

@Data
public class SearchResponseData {

    String site;

    String siteName;

    String uri;

    String title;

    String snippet;

    float relevance = 1.0f;

}
