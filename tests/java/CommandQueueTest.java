import com.clean.stickman.core.CommandQueue;

public class CommandQueueTest {
    public void testFifoOrder() {
        CommandQueue q = new CommandQueue();
        q.add("a"); q.add("b"); q.add("c");
        T.eq("a", q.poll(), "1"); T.eq("b", q.poll(), "2"); T.eq("c", q.poll(), "3");
        T.eq(null, q.poll(), "empty"); T.yes(q.isEmpty(), "isEmpty");
    }
    public void testCollapsesConsecutiveDuplicates() {
        CommandQueue q = new CommandQueue();
        q.add("a"); q.add("a"); q.add("b"); q.add("a");
        T.eq("a", q.poll(), "1"); T.eq("b", q.poll(), "2"); T.eq("a", q.poll(), "3");
        T.eq(null, q.poll(), "end");
    }
}
