package bl0.bl0jv2.runtime.device;

/** how big the screen is, for the guest to read: columns and rows (the host sets them) */
public final class DisplayController implements PortDevice {
    public static final int COLUMNS_PORT = 0x0F50;
    public static final int ROWS_PORT = 0x0F52;

    private volatile int columns = 80;
    private volatile int rows = 24;

    public void setSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    @Override
    public boolean claimsRead(int port) {
        return port == COLUMNS_PORT || port == ROWS_PORT;
    }

    @Override
    public long read(int port, int widthBytes) {
        return port == COLUMNS_PORT ? columns : rows;
    }
}
