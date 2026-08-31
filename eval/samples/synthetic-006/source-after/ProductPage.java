public class ProductPage {
    public Page render(List<Product> products, PriceService prices) {
        String title = "Products (" + products.size() + ")";
        return Page.of(products.stream().map(p -> prices.lookup(p.id())).toList());
    }
}
