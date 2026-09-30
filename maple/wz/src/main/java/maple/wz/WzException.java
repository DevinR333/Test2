package maple.wz;

public class WzException extends RuntimeException {
    public WzException(String msg) { super(msg); }
    public WzException(String msg, Throwable cause) { super(msg, cause); }
}
