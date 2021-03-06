package theWorst.requests;

import arc.struct.Array;
import arc.struct.ArrayMap;

public class Requester {
    final String MAX_TRANSPORT = "max_transport";
    final String THREAD_COUNT = "thread_count";

    Array<Request> requests = new Array<>();
    ArrayMap<String, Integer> config = new ArrayMap<>();

    public Requester() {
        config.put(MAX_TRANSPORT, 5000);
        config.put(THREAD_COUNT, 3);
    }

    public Array<Request> getRequests(){
        return requests;
    }

    void fail(String object, int amount){}

    public ArrayMap<String, Integer> getConfig() {
        return config;
    }
}
