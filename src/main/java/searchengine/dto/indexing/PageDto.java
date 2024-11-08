package searchengine.dto.indexing;

import lombok.Data;
import org.jsoup.select.Elements;
import searchengine.model.Page;
import searchengine.model.Site;

@Data
public class PageDto {

    private Integer id;

    private Site site;  // веб-сайт из таблицы site;

    private String path;  // адрес страницы от корня сайта
    // (должен начинаться со слэша, например: /news/372189/);

    private Integer code;  // код HTTP-ответа, полученный при запросе
    // страницы (например, 200, 404, 500 или другие);

    private String content = "";  // контент страницы (HTML-код)

    private Elements links;  // ссылки на другие страницы

    public Page toEntity() {
        Page page = new Page();
        page.setId(id);
        page.setSite(site);
        page.setPath(path);
        page.setCode(code);
        page.setContent(content);
        return page;
    }
}
