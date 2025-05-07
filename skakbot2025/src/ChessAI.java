import Board.Board;
import Pieces.Piece;
import java.util.*;

public class ChessAI {
    private static final int MAX_DEPTH = 10;
    private static final long TIME_LIMIT = 14_900_000_000L; // 15 seconds
    private static final int ASPIRATION_WINDOW_VALUE = 100; // 50 to 100 is normal, if you want consistently 5 depth and try to avoid depth 4, go 150

    // Center control bonus table
    private static final int[][] CENTER_CONTROL_BONUS = {
            {0, 0, 5, 5, 5, 5, 0, 0},
            {0, 5,10,10,10,10, 5, 0},
            {5,10,15,20,20,15,10, 5},
            {5,10,20,25,25,20,10, 5},
            {5,10,20,25,25,20,10, 5},
            {5,10,15,20,20,15,10, 5},
            {0, 5,10,10,10,10, 5, 0},
            {0, 0, 5, 5, 5, 5, 0, 0},
    };

    private int getPieceValue(Piece p) {
        return switch (p.getClass().getSimpleName().toLowerCase()) {
            case "pawn"   -> 100;
            case "knight" -> 320;
            case "bishop" -> 330;
            case "rook"   -> 500;
            case "queen"  -> 900;
            case "king"   -> 20000;
            default       -> 0;
        };
    }

    // Ordering bonuses
    private static final int PV_BONUS      = 1_000_000;
    private static final int KILLER1_BONUS =   8_000;
    private static final int KILLER2_BONUS =   7_000;

    private final Move[][] killerMoves       = new Move[2][MAX_DEPTH];
    private final int[][]   historyHeuristic = new int[64][64];
    private long cutoffCount = 0;
    private int  lastDepthReached = 0;

    public static class Move {
        public final int fromRow, fromCol, toRow, toCol;
        public final Piece movedPiece, capturedPiece;
        public Move(int fr, int fc, int tr, int tc, Piece m, Piece c) {
            fromRow = fr; fromCol = fc;
            toRow   = tr; toCol   = tc;
            movedPiece    = m;
            capturedPiece = c;
        }
        public int fromIndex() { return fromRow * 8 + fromCol; }
        public int toIndex()   { return toRow   * 8 + toCol;   }
    }

    public void calculateAndMakeMoveAsync(Board board, boolean isWhite, Runnable onMoveComplete) {
        new Thread(() -> {
            Move best = getBestMoveWithTimeout(board, isWhite, TIME_LIMIT);
            if (best != null) {
                makeMove(board, best, isWhite);
                System.out.println("α/β cutoffs: " + cutoffCount + ", Depth finished: " + lastDepthReached
                );
            }
            if (onMoveComplete != null) {
                onMoveComplete.run();
            }
        }).start();
    }

    // Iterative deepening + timeout
    private Move getBestMoveWithTimeout(Board board, boolean isWhite, long timeLimit) {
        long start = System.nanoTime();
        cutoffCount      = 0;
        lastDepthReached = 0;

        Move bestSoFar = null;
        Move prevPV    = null;
        int   lastScore = 0;

        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            if (System.nanoTime() - start >= timeLimit) break;

            // 1) set up aspiration window
            int alpha, beta;
            if (depth == 1) {
                alpha = Integer.MIN_VALUE;
                beta  = Integer.MAX_VALUE;
            } else {
                int window = ASPIRATION_WINDOW_VALUE;
                alpha = lastScore - window;
                beta  = lastScore + window;
            }

            // 2) check with aspiration window
            ScoredMove sm = minimaxWithTimeout(
                    board, depth,
                    alpha, beta,
                    isWhite, start, timeLimit,
                    prevPV
            );

            // 3) if it “failed” outside the window, re-search full
            if (sm != null
                    && (sm.score <= alpha || sm.score >= beta)
            ) {
                // window too small → full re-search
                sm = minimaxWithTimeout(
                        board, depth,
                        Integer.MIN_VALUE, Integer.MAX_VALUE,
                        isWhite, start, timeLimit,
                        prevPV
                );
            }

            if (sm == null) break;
            lastScore = sm.score;
            lastDepthReached = depth;

            if (sm.move != null) {
                bestSoFar = sm.move;
                prevPV = sm.move;
            }
        }
        return bestSoFar;
    }


    private static class ScoredMove {
        final Move move;
        final int  score;
        ScoredMove(Move m, int s) { move = m; score = s; }
    }

    // Minimax + alpha-beta + timeout
    private ScoredMove minimaxWithTimeout(
            Board board,
            int depth,
            int alpha,
            int beta,
            boolean maxPlayer,
            long start,
            long timeLimit,
            Move pv
    ) {
        if (System.nanoTime() - start >= timeLimit) return null;
        if (depth == 0) {
            return new ScoredMove(null, evaluateBoard(board));
        }

        List<Move> moves = generateAllMoves(board, maxPlayer, depth, pv);
        if (moves.isEmpty()) {
            return new ScoredMove(null, evaluateBoard(board));
        }

        Move bestMove = null;

        if (maxPlayer) {
            int maxEval = Integer.MIN_VALUE;
            for (Move m : moves) {
                if (System.nanoTime() - start >= timeLimit) return null;
                makeMove(board, m, true);
                ScoredMove child = minimaxWithTimeout(
                        board, depth - 1, alpha, beta, false,
                        start, timeLimit, null
                );
                undoMove(board, m);
                if (child == null) return null;

                if (child.score > maxEval) {
                    maxEval  = child.score;
                    bestMove = m;
                }
                alpha = Math.max(alpha, child.score);
                if (beta <= alpha) {
                    cutoffCount++;
                    recordKiller(m, depth);
                    break;
                }
                recordHistory(m, depth);
            }
            return new ScoredMove(bestMove, maxEval);

        } else {
            int minEval = Integer.MAX_VALUE;
            for (Move m : moves) {
                if (System.nanoTime() - start >= timeLimit) return null;
                makeMove(board, m, false);
                ScoredMove child = minimaxWithTimeout(
                        board, depth - 1, alpha, beta, true,
                        start, timeLimit, null
                );
                undoMove(board, m);
                if (child == null) return null;

                if (child.score < minEval) {
                    minEval   = child.score;
                    bestMove  = m;
                }
                beta = Math.min(beta, child.score);
                if (beta <= alpha) {
                    cutoffCount++;
                    recordKiller(m, depth);
                    break;
                }
                recordHistory(m, depth);
            }
            return new ScoredMove(bestMove, minEval);
        }
    }

    private void recordKiller(Move m, int depth) {
        int d = depth - 1;
        killerMoves[1][d] = killerMoves[0][d];
        killerMoves[0][d] = m;
    }

    private void recordHistory(Move m, int depth) {
        if (m.capturedPiece == null) {
            historyHeuristic[m.fromIndex()][m.toIndex()] += depth * depth;
        }
    }

    /**
     * Multi-tier move ordering:
     * 1) PV move (root only)
     * 2) MVV-LVA captures
     * 3) Checks
     * 4) Killer moves at this depth
     * 5) History heuristic
     * 6) Center control
     */
    public List<Move> generateAllMoves(
            Board board,
            boolean isWhite,
            int depth,
            Move pv
    ) {
        List<Move> moves = new ArrayList<>();
        Map<Move,Integer> scores = new HashMap<>();
        Piece[][] b = board.board;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c];
                if (p == null || p.isWhite() != isWhite) continue;
                for (int tr = 0; tr < 8; tr++) {
                    for (int tc = 0; tc < 8; tc++) {
                        if (!p.isValidMove(tr, tc, b)) continue;
                        Piece cap = b[tr][tc];
                        Move m = new Move(r, c, tr, tc, p, cap);
                        if (!makeMove(board, m, isWhite)) continue;

                        int sc = 0;
                        if (pv != null && m == pv) {
                            sc += PV_BONUS;
                        }
                        if (cap != null) {
                            sc += 10_000 + getPieceValue(cap) - getPieceValue(p);
                        } else if (board.isInCheck(!isWhite)) {
                            sc += 5_000;
                        }
                        int d = depth - 1;
                        if (killerMoves[0][d] == m) sc += KILLER1_BONUS;
                        if (killerMoves[1][d] == m) sc += KILLER2_BONUS;
                        sc += historyHeuristic[m.fromIndex()][m.toIndex()];
                        sc += CENTER_CONTROL_BONUS[tr][tc];

                        moves.add(m);
                        scores.put(m, sc);
                        undoMove(board, m);
                    }
                }
            }
        }

        moves.sort((m1, m2) -> scores.get(m2) - scores.get(m1));
        return moves;
    }

    // Execute move if legal
    private boolean makeMove(Board board, Move m, boolean isWhite) {
        if (!m.movedPiece.isValidMove(m.toRow, m.toCol, board.board)) return false;
        Piece orig = board.board[m.toRow][m.toCol];
        board.board[m.toRow][m.toCol] = m.movedPiece;
        board.board[m.fromRow][m.fromCol] = null;
        m.movedPiece.setPosition(m.toRow, m.toCol);
        if (board.isInCheck(isWhite)) {
            board.board[m.fromRow][m.fromCol] = m.movedPiece;
            board.board[m.toRow][m.toCol] = orig;
            m.movedPiece.setPosition(m.fromRow, m.fromCol);
            return false;
        }
        return true;
    }

    private void undoMove(Board board, Move m) {
        board.board[m.fromRow][m.fromCol] = m.movedPiece;
        board.board[m.toRow][m.toCol] = m.capturedPiece;
        m.movedPiece.setPosition(m.fromRow, m.fromCol);
    }

    private int evaluateBoard(Board board) {
        int score = 0;
        Piece[][] b = board.board;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c];
                if (p != null) {
                    int v     = getPieceValue(p);
                    int bonus = CENTER_CONTROL_BONUS[r][c];
                    score += p.isWhite() ? (v + bonus) : -(v + bonus);
                }
            }
        }
        return score;
    }
}
