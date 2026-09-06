package ua.rp.chat;

import net.minecraft.core.BlockPos;
import ua.rp.chat.carver.CarverChalkQuads;
import ua.rp.chat.carver.DraftMask;
import ua.rp.chat.client.carver.ObservedDraftBoard;

public final class ObservedDraftBoardTest {
    public static void main(String[] args) {
        verifyPutAndSnapshot();
        verifyNullsIgnored();
        verifyRemoveByEqualPos();
        verifyOverwrite();
        verifySnapshotIsolation();
        verifyExpiry();
        verifyClear();
        System.out.println("ObservedDraftBoardTest passed");
    }

    private static void verifyPutAndSnapshot() {
        ObservedDraftBoard board = new ObservedDraftBoard();
        DraftMask mask = new DraftMask();
        mask.set(DraftMask.index(1, 2, 3));
        mask.set(DraftMask.index(4, 5, 6));
        board.put(new BlockPos(7, 80, 9), mask, 100L);
        require(board.size() == 1 && !board.isEmpty(), "Board must hold one entry");
        ObservedDraftBoard.Entry entry = board.snapshot().get(0);
        require(entry.focus().equals(new BlockPos(7, 80, 9)), "Entry must keep focus");
        require(entry.lastTick() == 100L, "Entry must keep tick");
        require(entry.fingerprint() == CarverChalkQuads.draftFingerprint(mask),
                "Entry fingerprint must match mask");
        require(entry.mask().equals(mask) && entry.mask() != mask,
                "Entry must hold a defensive copy");
        mask.set(DraftMask.index(0, 0, 0));
        require(entry.mask().count() == 2, "Later edits must not leak into the snapshot");
    }

    private static void verifyNullsIgnored() {
        ObservedDraftBoard board = new ObservedDraftBoard();
        board.put(null, new DraftMask(), 0L);
        board.put(new BlockPos(0, 0, 0), null, 0L);
        board.remove(null);
        require(board.isEmpty(), "Null puts and removes must be ignored");
    }

    private static void verifyRemoveByEqualPos() {
        ObservedDraftBoard board = new ObservedDraftBoard();
        DraftMask mask = new DraftMask();
        mask.set(10);
        board.put(new BlockPos(1, 2, 3), mask, 0L);
        board.remove(new BlockPos(1, 2, 3));
        require(board.isEmpty(), "Remove must match by position equality");
    }

    private static void verifyOverwrite() {
        ObservedDraftBoard board = new ObservedDraftBoard();
        DraftMask first = new DraftMask();
        first.set(1);
        DraftMask second = new DraftMask();
        second.set(2);
        second.set(3);
        board.put(new BlockPos(5, 5, 5), first, 10L);
        board.put(new BlockPos(5, 5, 5), second, 20L);
        require(board.size() == 1, "Same focus must overwrite, not duplicate");
        ObservedDraftBoard.Entry entry = board.snapshot().get(0);
        require(entry.mask().equals(second) && entry.lastTick() == 20L,
                "Overwrite must refresh mask and tick");
    }

    private static void verifySnapshotIsolation() {
        ObservedDraftBoard board = new ObservedDraftBoard();
        DraftMask mask = new DraftMask();
        mask.set(7);
        board.put(new BlockPos(9, 9, 9), mask, 0L);
        try {
            board.snapshot().clear();
            throw new AssertionError("Snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
        require(board.size() == 1, "Clearing the snapshot must not touch the board");
    }

    private static void verifyExpiry() {
        ObservedDraftBoard board = new ObservedDraftBoard();
        DraftMask mask = new DraftMask();
        mask.set(1);
        board.put(new BlockPos(0, 0, 0), mask, 100L);
        board.put(new BlockPos(1, 1, 1), mask, 150L);
        board.expire(301L, 200L);
        require(board.size() == 1, "Only the stale entry must expire, got " + board.size());
        require(board.snapshot().get(0).focus().equals(new BlockPos(1, 1, 1)),
                "Fresh entry must survive expiry");
        board.expire(351L, 200L);
        require(board.isEmpty(), "Lapsed entry must expire");
    }

    private static void verifyClear() {
        ObservedDraftBoard board = new ObservedDraftBoard();
        DraftMask mask = new DraftMask();
        mask.set(1);
        board.put(new BlockPos(2, 2, 2), mask, 0L);
        board.clear();
        require(board.isEmpty(), "Clear must drop every entry");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
