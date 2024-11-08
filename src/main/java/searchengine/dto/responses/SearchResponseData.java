package searchengine.dto.responses;

import lombok.Data;
import searchengine.model.Page;
import searchengine.model.Site;

@Data
public class SearchResponseData {

    private String site;

    private String siteName;

    private String uri;

    private String title;

    private String snippet;

    private float relevance = 1.0f;

    public SearchResponseData(Page page) {
        Site pageSite = page.getSite();
        site = pageSite.getUrl();
        siteName = pageSite.getName();
        uri = page.getPath();
        title = page.getTitle();
    }

}
