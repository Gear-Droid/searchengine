package searchengine.services.searching;

import searchengine.dto.responses.SearchResultResponseDto;

public interface SearchingService {

    SearchResultResponseDto getSearchResults(String query, String site, Integer offset, Integer limit);

}
