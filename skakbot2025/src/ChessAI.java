import Board.Board;
import Pieces.Piece;
import java.util.*;

public class ChessAI {
    private static final int MAX_DEPTH = 10;
    private static final long TIME_LIMIT = 14_900_000_000L; // 15 seconds
    private static final int ASPIRATION_WINDOW_VALUE = 100; // ±100 cp

    // Score for checkmate
    private static final int MATE_SCORE = 1_000_000;

    // Center-control bonus table
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

    // Ordering bonuses
    private static final int PV_BONUS      = 1_000_000;
    private static final int KILLER1_BONUS =   8_000;
    private static final int KILLER2_BONUS =   7_000;

    private final Move[][] killerMoves       = new Move[2][MAX_DEPTH];
    private final int[][]   historyHeuristic = new int[64][64];

    private long cutoffCount      = 0;
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
        public int fromIndex() { return fromRow*8 + fromCol; }
        public int toIndex()   { return toRow*8   + toCol;   }
    }

    public void calculateAndMakeMoveAsync(Board board, boolean isWhite, Runnable onMoveComplete) {
        new Thread(() -> {
            Move best = getBestMoveWithTimeout(board, isWhite, TIME_LIMIT);
            if (best != null) {
                makeMove(board, best, isWhite);
                System.out.println("α/β cutoffs: " + cutoffCount +
                        ", Depth finished: " + lastDepthReached);
            }
            if (onMoveComplete != null) onMoveComplete.run();
        }).start();
    }

    // Iterative deepening + aspiration + timeout
    private Move getBestMoveWithTimeout(Board board, boolean isWhite, long timeLimit) {
        long start = System.nanoTime();
        cutoffCount      = 0;
        lastDepthReached = 0;

        Move bestSoFar = null, prevPV = null;
        int   lastScore = 0;

        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            if (System.nanoTime() - start >= timeLimit) break;

            int alpha, beta;
            if (depth == 1) {
                alpha = Integer.MIN_VALUE;
                beta  = Integer.MAX_VALUE;
            } else {
                alpha = lastScore - ASPIRATION_WINDOW_VALUE;
                beta  = lastScore + ASPIRATION_WINDOW_VALUE;
            }

            ScoredMove sm = minimaxWithTimeout(
                    board, depth, alpha, beta, isWhite,
                    start, timeLimit, prevPV
            );
            if (sm != null && (sm.score <= alpha || sm.score >= beta)) {
                sm = minimaxWithTimeout(
                        board, depth,
                        Integer.MIN_VALUE, Integer.MAX_VALUE,
                        isWhite, start, timeLimit,
                        prevPV
                );
            }
            if (sm == null) break;          // timed out

            lastScore        = sm.score;
            lastDepthReached = depth;       // completed

            if (sm.move != null) {
                bestSoFar = sm.move;
                prevPV    = sm.move;
                // if mate found for this side, stop deeper search
                if ((isWhite  && sm.score ==  MATE_SCORE) ||
                        (!isWhite && sm.score == -MATE_SCORE)) {
                    break;
                }
            }
        }
        return bestSoFar;
    }

    private static class ScoredMove {
        final Move move;
        final int  score;
        ScoredMove(Move m, int s) { move = m; score = s; }
    }

    // Minimax + alpha-beta + timeout + mate detection + early mate cut-off
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
            if (board.isCheckmate(maxPlayer)) {
                int v = maxPlayer ? -MATE_SCORE : +MATE_SCORE;
                return new ScoredMove(null, v);
            } else {
                return new ScoredMove(null, 0);
            }
        }

        if (maxPlayer) {
            Move bestMove = null;
            int   maxEval = Integer.MIN_VALUE;
            for (Move m : moves) {
                if (System.nanoTime() - start >= timeLimit) return null;
                makeMove(board, m, true);
                ScoredMove child = minimaxWithTimeout(
                        board, depth - 1, alpha, beta,
                        false, start, timeLimit, null
                );
                undoMove(board, m);
                if (child == null) return null;

                // early mate cut-off
                if (child.score == MATE_SCORE) {
                    return new ScoredMove(m, MATE_SCORE);
                }

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
            Move bestMove = null;
            int   minEval = Integer.MAX_VALUE;
            for (Move m : moves) {
                if (System.nanoTime() - start >= timeLimit) return null;
                makeMove(board, m, false);
                ScoredMove child = minimaxWithTimeout(
                        board, depth - 1, alpha, beta,
                        true, start, timeLimit, null
                );
                undoMove(board, m);
                if (child == null) return null;

                // early mate cut-off
                if (child.score == -MATE_SCORE) {
                    return new ScoredMove(m, -MATE_SCORE);
                }

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
     * Move‐ordering tiers:
     * 1) root PV
     * 2) MVV‐LVA captures
     * 3) checks
     * 4) killer moves
     * 5) history heuristic
     * 6) center control
     */
    public List<Move> generateAllMoves(
            Board board,
            boolean isWhite,
            int depth,
            Move pv
    ) {
        List<Move> moves = new ArrayList<>();
        Map<Move,Integer> scores = new HashMap<>();
        Piece[][] boardArray = board.board;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = boardArray[r][c];
                if (p == null || p.isWhite() != isWhite) continue;
                for (int tr = 0; tr < 8; tr++) {
                    for (int tc = 0; tc < 8; tc++) {
                        if (!p.isValidMove(tr, tc, boardArray)) continue;
                        Piece cap = boardArray[tr][tc];
                        Move m = new Move(r, c, tr, tc, p, cap);
                        if (!makeMove(board, m, isWhite)) continue;

                        int sc = 0;
                        if (pv != null && m == pv) sc += PV_BONUS;
                        if (cap != null) sc += 10_000 + getPieceValue(cap) - getPieceValue(p);
                        else if (board.isInCheck(!isWhite)) sc += 5_000;

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

        moves.sort((a, b) -> scores.get(b) - scores.get(a));
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

    // Static evaluation
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

    // Piece base values
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
}
