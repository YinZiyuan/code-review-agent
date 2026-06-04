public class ProductPage {
    public Page render(List<Product> products, PriceService prices) {
        Map<String, Price> all = prices.bulk(products);
        return Page.of(products.stream().map(p -> all.get(p.id())).toList());
    }
}
