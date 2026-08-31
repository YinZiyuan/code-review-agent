public class SearchController {
    public String safe(String term) {
        String label = "search:" + term.replace("\n", " ");
        return db.query("select * from docs where title = '" + term + "'");
    }
}
