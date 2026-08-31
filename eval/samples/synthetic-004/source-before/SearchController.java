public class SearchController {
    public String safe(String term) {
        return db.query("select * from docs where title = ?", term);
    }
}
