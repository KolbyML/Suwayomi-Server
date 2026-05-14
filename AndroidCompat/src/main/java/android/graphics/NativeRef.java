package android.graphics;

public class NativeRef {
    private volatile long address;

    public NativeRef(long address) {
        this.address = address;
    }

    public long address() {
        return address;
    }

    public void clear() {
        address = 0;
    }
}
