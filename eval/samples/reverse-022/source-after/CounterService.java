public class CounterService {
    private int count;

    public int next() {
        count += 1;
        return count;
    }
}
