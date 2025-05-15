import Board.Board;
import Evaluation.SimpleEvaluation;
import Board.BitboardBoard;
import Pieces.Piece;
import Pieces.Pawn;
import Pieces.Knight;
import Pieces.Bishop;
import Pieces.Rook;
import Pieces.Queen;
import Pieces.King;

import java.util.*;

public class ChessAI {
    private static final int MAX_DEPTH = 15;
    private static final long TIME_LIMIT_NANO = 14_950_000_000L;
    private static final int ASPIRATION_WINDOW_VALUE = 150;
    private static final int MATE_SCORE = 1_000_000;

    private static final int[][] ROOK_DIRS   = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final int[][] BISHOP_DIRS = {{1,1},{1,-1},{-1,1},{-1,-1}};
    private static final int[][] QUEEN_DIRS;
    static {
        QUEEN_DIRS = new int[ROOK_DIRS.length + BISHOP_DIRS.length][2];
        System.arraycopy(ROOK_DIRS, 0, QUEEN_DIRS, 0, ROOK_DIRS.length);
        System.arraycopy(BISHOP_DIRS, 0, QUEEN_DIRS, ROOK_DIRS.length, BISHOP_DIRS.length);
    }
    private static final int[][] KNIGHT_JUMPS = {{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{1,-2},{-1,2},{-1,-2}};
    private static final int[][] KING_STEPS   = QUEEN_DIRS;
    private static final int[][] CENTER_CONTROL_BONUS = {
            {0,0,5,5,5,5,0,0},
            {0,5,10,10,10,10,5,0},
            {5,10,15,20,20,15,10,5},
            {5,10,20,25,25,20,10,5},
            {5,10,20,25,25,20,10,5},
            {5,10,15,20,20,15,10,5},
            {0,5,10,10,10,10,5,0},
            {0,0,5,5,5,5,0,0},
    };
    private static final int ENDGAME_PIECE_THRESHOLD = 12;
    private static final int CHECK_BONUS = 50;
    private static final int KING_ACTIVITY_WEIGHT    = 20;
    private static final int MOBILITY_WEIGHT         = 4;
    private static final int PASSED_PAWN_WEIGHT      = 20;
    private static final int PV_BONUS      = 1_000_000;
    private static final int KILLER1_BONUS =   8_000;
    private static final int KILLER2_BONUS =   7_000;
    private long currentHash;
    private static final long[] ZOBRIST_SLICE_KEYS = new long[16];
    static {
        Random rnd = new Random(0xDEADBEEFL);
        for (int i = 0; i < 16; i++) {
            ZOBRIST_SLICE_KEYS[i] = rnd.nextLong();
        }
    }

    private static final long[][] ZOBRIST_PIECE_KEYS = new long[12][64];
    private static final long   ZOBRIST_SIDE_TO_MOVE;
    static {
        Random rnd = new Random(0xDEADBEEFL);
        for (int i = 0; i < 12; i++) {
            for (int sq = 0; sq < 64; sq++) {
                ZOBRIST_PIECE_KEYS[i][sq] = rnd.nextLong();
            }
        }
        ZOBRIST_SIDE_TO_MOVE = rnd.nextLong();

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                CENTER_CONTROL_BONUS[r][c] /= 2;
            }
        }
    }

    private static boolean tablesInitialized = false;
    private static synchronized void ensureLookupTables() {
        if (!tablesInitialized) {
            new BitboardBoard().generateLookupTables();
            tablesInitialized = true;
        }
    }

    private boolean inBounds(int r, int c) {
        return r >= 0 && r < 8 && c >= 0 && c < 8;
    }

    private static class TTEntry {
        int depth, value, flag;
        int bestMove;
    }
    private final Map<Long,TTEntry> tt = new HashMap<>();
    private final SimpleEvaluation evaluator = new SimpleEvaluation();
    private long[] boardState;

    private final Move[][] killerMoves       = new Move[2][MAX_DEPTH];
    private final int[][]   historyHeuristic = new int[64][64];

    private long cutoffCount      = 0;
    private int  lastDepthReached = 0;

    public static class Move {
        public final int fromRow, fromCol, toRow, toCol;
        public final Piece movedPiece, capturedPiece;
        public final Piece promotion;

        public Move(int fr, int fc, int tr, int tc,
                    Piece m, Piece c, Piece promotion) {
            fromRow = fr; fromCol = fc;
            toRow   = tr; toCol   = tc;
            movedPiece    = m;
            capturedPiece = c;
            this.promotion = promotion;
        }

        public Move(int fr, int fc, int tr, int tc, Piece m, Piece c) {
            this(fr,fc,tr,tc,m,c,null);
        }

        public int fromIndex() { return fromRow*8 + fromCol; }
        public int toIndex()   { return toRow*8   + toCol;   }
    }

    public void calculateAndMakeMoveAsync(Board board, boolean isWhite, Runnable onMoveComplete) {
        new Thread(() -> {
            // ensure our sliding‐piece tables are built once
            ensureLookupTables();

            // pack the GUI Board into our 16‐slice bitboard
            boardState  = boardToBitboard(board, isWhite);
            currentHash = computeZobrist(boardState);
            tt.clear();

            // run a timed minimax search, giving it the same isWhite
            Map<Long,Integer> rootRepeats = new HashMap<>();
            rootRepeats.put(currentHash, 1);
            int bestEnc = searchWithTimeout(boardState, isWhite, TIME_LIMIT_NANO, rootRepeats);

            // apply bestEnc, rebuild a fresh 2D Board, stash into GUI
            if (bestEnc != -1) {
                boardState = BitboardBoard.makeOrUndoMove(boardState, bestEnc);
                Board newBoard = BitboardBoard.bitboardToBoard(boardState);
                board.board   = newBoard.board;
            }

            // notify caller
            if (onMoveComplete != null) onMoveComplete.run();
        }).start();
    }

    // 2) update your packer to accept that flag
    private long[] boardToBitboard(Board b, boolean whiteToMove) {
        long[] s = new long[16];
        Arrays.fill(s, 0L);

        // misc data: whiteToMove, castling rights, no en passant
        boolean[] cr = {
                b.canCastle(true,  +1),
                b.canCastle(true,  -1),
                b.canCastle(false, +1),
                b.canCastle(false, -1)
        };
        s[15] = BitboardBoard.encodeMiscData(whiteToMove, cr, -1);

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b.board[r][c];
                if (p == null) continue;
                int sq   = (r*8 + c) ^ 56;   // flip A1→LSB
                long m   = 1L << sq;
                s[0]    |= m;
                if (p.isWhite()) s[1] |= m; else s[2] |= m;

                int type = switch(p.getClass().getSimpleName().toLowerCase()) {
                    case "pawn"   -> 1;
                    case "knight" -> 2;
                    case "bishop" -> 3;
                    case "rook"   -> 4;
                    case "queen"  -> 5;
                    case "king"   -> 6;
                    default       -> 0;
                };
                int idx = p.isWhite() ? (2 + type) : (8 + type);
                s[idx] |= m;
            }
        }

        return s;
    }

    private int searchWithTimeout(long[] state, boolean isWhite, long timeLimit, Map<Long,Integer> rootRepeats) {
        long start = System.nanoTime();
        int lastScore = 0, bestMove = -1;

        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            if (System.nanoTime() - start > timeLimit) break;

            int alpha = (depth == 1 ? Integer.MIN_VALUE : lastScore - 150);
            int beta  = (depth == 1 ? Integer.MAX_VALUE : lastScore + 150);

            ScoredMove sm = minimax(state, depth, alpha, beta, isWhite, start, timeLimit, rootRepeats, depth);
            if (sm == null) break;  // timed out

            // re-search if outside window
            if (sm.score <= alpha || sm.score >= beta) {
                sm = minimax(state, depth, Integer.MIN_VALUE, Integer.MAX_VALUE,
                        isWhite, start, timeLimit, rootRepeats, depth);
                if (sm == null) break;
            }

            lastScore = sm.score;
            bestMove  = sm.move;
        }
        return bestMove;
    }

    private static class ScoredMove { final int move, score; ScoredMove(int m,int s){move=m;score=s;} }

    private ScoredMove minimax(
            long[]              state,
            int                 depth,
            int                 alpha,
            int                 beta,
            boolean             maxPlayer,
            long                start,
            long                timeLimit,
            Map<Long,Integer>   rootRepeats,
            int                 rootDepth
    ) {
        if (System.nanoTime() - start > timeLimit) return null;

        if (depth == 0) {
            int stand = evaluator.simpleEvaluation(state);
            return new ScoredMove(-1, stand);
        }

        TTEntry ent = tt.get(currentHash);
        if (ent != null && ent.depth >= depth) {
            if (ent.flag == 0) return new ScoredMove(ent.bestMove, ent.value);
            if (ent.flag == 1) alpha = Math.max(alpha, ent.value);
            if (ent.flag == 2) beta  = Math.min(beta, ent.value);
            if (alpha >= beta) return new ScoredMove(ent.bestMove, ent.value);
        }

        if (depth == rootDepth) {
            rootRepeats.put(currentHash, rootRepeats.getOrDefault(currentHash,0) + 1);
        }

        int[] moves = BitboardBoard.getAllMoves(state, maxPlayer);
        if (moves.length == 0) {
            int mateScore = maxPlayer ? -MATE_SCORE : +MATE_SCORE;
            return new ScoredMove(-1, mateScore);
        }

        int bestScore = maxPlayer ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        int bestMove  = -1;

        for (int m : moves) {
            // --- make the move (in-place) ---
            long oldHash = currentHash;
            BitboardBoard.makeOrUndoMove(state, m);
            currentHash = computeZobrist(state);

            // --- search the child ---
            ScoredMove child = minimax(
                    state, depth-1, alpha, beta,
                    !maxPlayer, start, timeLimit,
                    rootRepeats, rootDepth
            );

            // --- undo the move (same call) ---
            BitboardBoard.makeOrUndoMove(state, m);
            currentHash = oldHash;

            if (child == null) return null;  // timeout

            int score = child.score;
            if (maxPlayer ? (score > bestScore) : (score < bestScore)) {
                bestScore = score;
                bestMove  = m;
            }
            if (maxPlayer) alpha = Math.max(alpha, score);
            else          beta  = Math.min(beta, score);
            if (alpha >= beta) break;
        }

        // store into TT
        TTEntry ne = new TTEntry();
        ne.depth    = depth;
        ne.value    = bestScore;
        ne.bestMove = bestMove;
        ne.flag     = ( bestScore <= alpha ? 2 :
                bestScore >= beta  ? 1 : 0 );
        tt.put(currentHash, ne);

        return new ScoredMove(bestMove, bestScore);
    }

    private long computeZobrist(long[] state) {
        long h = 0;
        for (int i = 0; i < 16; i++) {
            h ^= state[i] * ZOBRIST_SLICE_KEYS[i];
        }
        return h;
    }

    private boolean makeMove(Board board, Move m, boolean isWhite) {
        Piece piece = m.movedPiece;
        int fromIdx = m.fromIndex();
        int toIdx   = m.toIndex();

        // --- remove side-to-move to flip after move ---
        currentHash ^= ZOBRIST_SIDE_TO_MOVE;

        // --- CASTLING ---
        if (piece instanceof King && Math.abs(m.toCol - m.fromCol) == 2) {
            int dir = (m.toCol > m.fromCol) ? 1 : -1;
            if (!board.canCastle(isWhite, dir)) {
                // restore side-to-move
                currentHash ^= ZOBRIST_SIDE_TO_MOVE;
                return false;
            }

            // XOR out king from & rook from
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(piece)][fromIdx];
            int rookFrom = (dir == 1 ? 7 : 0);
            int rookTo   = m.fromCol + dir;
            Piece rook   = board.board[m.toRow][rookFrom];
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(rook)][m.toRow*8 + rookFrom];

            // make the move on board
            board.board[m.toRow][m.toCol]   = piece;
            board.board[m.fromRow][m.fromCol] = null;
            piece.move(m.toRow, m.toCol);

            board.board[m.toRow][rookTo]   = rook;
            board.board[m.toRow][rookFrom] = null;
            if (rook instanceof Rook) ((Rook) rook).move(m.toRow, rookTo);

            ((King) piece).setHasMoved(true);
            if (rook instanceof Rook) ((Rook) rook).setHasMoved(true);

            // XOR in king to & rook to
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(piece)][toIdx];
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(rook)][m.toRow*8 + rookTo];

            if (board.isInCheck(isWhite)) {
                undoMove(board, m);
                return false;
            }
            return true;
        }

        // --- non-castle valid test ---
        if (!piece.isValidMove(m.toRow, m.toCol, board.board)) {
            // restore side-to-move
            currentHash ^= ZOBRIST_SIDE_TO_MOVE;
            return false;
        }

        // --- XOR out moving piece at from ---
        currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(piece)][fromIdx];

        // --- handle capture ---
        if (m.capturedPiece != null) {
            Piece cap = m.capturedPiece;
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(cap)][toIdx];
        }

        // --- handle promotion vs normal move ---
        if (m.promotion != null) {
            // promotion: pawn removed from from & promotion added at to
            board.board[m.toRow][m.toCol] = m.promotion;
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(m.promotion)][toIdx];
        } else {
            // normal: piece added at to
            board.board[m.toRow][m.toCol] = piece;
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(piece)][toIdx];
        }

        board.board[m.fromRow][m.fromCol] = null;
        piece.setPosition(m.toRow, m.toCol);

        if (board.isInCheck(isWhite)) {
            undoMove(board, m);
            return false;
        }
        return true;
    }

    private void undoMove(Board board, Move m) {
        Piece piece = m.movedPiece;
        int fromIdx = m.fromIndex();
        int toIdx   = m.toIndex();

        // --- remove side-to-move to flip back ---
        currentHash ^= ZOBRIST_SIDE_TO_MOVE;

        // --- CASTLING undo ---
        if (piece instanceof King && Math.abs(m.toCol - m.fromCol) == 2) {
            int dir      = (m.toCol > m.fromCol) ? 1 : -1;
            int rookTo   = m.fromCol + dir;
            int rookFrom = (dir == 1 ? 7 : 0);
            Piece rook   = board.board[m.toRow][rookTo];

            // XOR out king to & rook to
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(piece)][toIdx];
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(rook)][m.toRow*8 + rookTo];

            // put them back
            ((Rook) rook).setHasMoved(false);
            board.board[m.toRow][rookFrom] = rook;
            rook.setPosition(m.toRow, rookFrom);
            board.board[m.toRow][rookTo]   = null;

            ((King) m.movedPiece).setHasMoved(false);

            // XOR in king from & rook from
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(piece)][fromIdx];
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(rook)][m.toRow*8 + rookFrom];

            // restore board positions
            board.board[m.fromRow][m.fromCol] = m.movedPiece;
            m.movedPiece.setPosition(m.fromRow, m.fromCol);
            board.board[m.toRow][m.toCol] = m.capturedPiece;
            return;
        }

        // --- normal/promotion undo ---

        // XOR out piece at to (or promotion)
        if (m.promotion != null) {
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(m.promotion)][toIdx];
        } else {
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(piece)][toIdx];
        }

        // XOR in any captured piece at to
        if (m.capturedPiece != null) {
            currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(m.capturedPiece)][toIdx];
        }

        // XOR in moved piece back at from
        currentHash ^= ZOBRIST_PIECE_KEYS[pieceKey(piece)][fromIdx];

        // restore board
        if (m.promotion != null) {
            board.board[m.fromRow][m.fromCol] = m.movedPiece;
            m.movedPiece.setPosition(m.fromRow, m.fromCol);
        } else {
            board.board[m.fromRow][m.fromCol] = m.movedPiece;
            m.movedPiece.setPosition(m.fromRow, m.fromCol);
        }
        board.board[m.toRow][m.toCol] = m.capturedPiece;
    }

    // helper to map Piece→Zobrist index (0–11)
    private int pieceKey(Piece p) {
        int type = switch(p.getClass().getSimpleName().toLowerCase()) {
            case "pawn"   -> 0;
            case "knight" -> 1;
            case "bishop" -> 2;
            case "rook"   -> 3;
            case "queen"  -> 4;
            case "king"   -> 5;
            default       -> -1;
        };
        int colorOff = p.isWhite() ? 0 : 6;
        return colorOff + type;
    }
}