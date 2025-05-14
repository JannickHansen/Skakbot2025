package Main;

import Board.Board;
import Board.BitboardBoard;
import Evaluation.PieceSquareTables;
import Pieces.Piece;
import Pieces.Pawn;
import Pieces.King;

import Adapters.BoardAdapter;

import java.util.*;
import java.util.List;

public class ChessAI {
    private static final int MAX_DEPTH = 15;
    private static final long TIME_LIMIT = 14_950_000_000L;
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
    private static final PieceSquareTables PST = new PieceSquareTables();
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

    private static class TTEntry {
        int depth, value, flag;
        Move best;
    }
    private final Map<Long,TTEntry> tt = new HashMap<>();

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

        public int fromIndex() { return fromRow*8 + fromCol; }
        public int toIndex()   { return toRow*8   + toCol;   }
    }

    public void calculateAndMakeMoveAsync(
            BoardAdapter adapter,
            boolean isWhite,
            Runnable onMoveComplete
    ) {
        new Thread(() -> {
            currentHash = adapter.computeZobristHash();

            Map<Long,Integer> rootRepeats = new HashMap<>();
            rootRepeats.put(currentHash, 1);

            Move best = getBestMoveWithTimeout(
                    adapter, isWhite, TIME_LIMIT, rootRepeats
            );
            boolean moved = false;

            if (best != null) {
                moved = adapter.makeMove(best);
                if (moved) {
                    currentHash = adapter.computeZobristHash();
                    System.out.println("α/β cutoffs: " + cutoffCount +
                            ", Depth finished: " + lastDepthReached);
                } else {
                    System.err.println("⚠️ Failed to apply best move: " + best);
                }
            }

            if (!moved) {
                List<Move> legal = adapter.generateAllMoves(1, null);
                if (!legal.isEmpty()) {
                    adapter.makeMove(legal.get(0));
                    System.out.println("✳️ Fallback move played.");
                } else {
                    System.out.println("♟ No legal moves available. Game over.");
                }
            }

            if (onMoveComplete != null) onMoveComplete.run();
        }).start();
    }

    private Move getBestMoveWithTimeout(
            BoardAdapter adapter,
            boolean isWhite,
            long timeLimit,
            Map<Long,Integer> rootRepeats
    ) {
        long start = System.nanoTime();
        cutoffCount      = 0;
        lastDepthReached = 0;
        tt.clear();

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

            // 1) Try with an aspiration window
            ScoredMove sm = minimaxWithTimeout(
                    adapter, depth, alpha, beta, isWhite,
                    start, timeLimit, prevPV, rootRepeats, depth
            );

            // 2) If we fell outside, re-search full window
            if (sm != null && (sm.score <= alpha || sm.score >= beta)) {
                sm = minimaxWithTimeout(
                        adapter, depth,
                        Integer.MIN_VALUE, Integer.MAX_VALUE,
                        isWhite, start, timeLimit,
                        prevPV, rootRepeats, depth
                );
            }
            if (sm == null) break;

            // 3) Accept the result
            lastScore        = sm.score;
            lastDepthReached = depth;
            if (sm.move != null) {
                bestSoFar = sm.move;
                prevPV    = sm.move;
            }
        }
        return bestSoFar;
    }


    private static class ScoredMove {
        final Move move; final int score;
        ScoredMove(Move m,int s){ move=m; score=s; }
    }

    private ScoredMove minimaxWithTimeout(
            BoardAdapter adapter,
            int depth,
            int alpha,
            int beta,
            boolean maxPlayer,
            long start,
            long timeLimit,
            Move pv,
            Map<Long,Integer> rootRepeats,
            int rootDepth
    ) {
        if (System.nanoTime() - start >= timeLimit) return null;
        if (depth < 0) depth = 0;

        // Early quiescence
        boolean winningMaterial = adapter.evaluate() * (maxPlayer ? 1 : -1) >= 1800;
        if (depth == 0 && !(winningMaterial || adapter.isInCheck())) {
            int q = quiesce(adapter, maxPlayer, alpha, beta);
            return new ScoredMove(null, q);
        }

        long key = currentHash;
        TTEntry ent = tt.get(key);
        if (ent != null && ent.depth >= depth) {
            if (ent.flag == 0) {
                return new ScoredMove(ent.best, ent.value);
            } else if (ent.flag == 1) {
                alpha = Math.max(alpha, ent.value);
            } else {
                beta = Math.min(beta, ent.value);
            }
            if (alpha >= beta) {
                return new ScoredMove(ent.best, ent.value);
            }
        }

        if (depth == rootDepth) {
            rootRepeats.put(key, rootRepeats.getOrDefault(key, 0) + 1);
        }

        int origAlpha = alpha, origBeta = beta;
        List<Move> moves = adapter.generateAllMoves(depth, pv);

        if (moves.isEmpty()) {
            int v = adapter.isInCheck()
                    ? (maxPlayer ? -MATE_SCORE : +MATE_SCORE)
                    : 0;
            return new ScoredMove(null, v);
        }

        Move bestMove = null;
        int bestScore = maxPlayer ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (Move m : moves) {
            if (System.nanoTime() - start >= timeLimit) return null;
            if (!adapter.makeMove(m)) continue;                   // try the move
            currentHash = adapter.computeZobristHash();           // update hash

            ScoredMove child = minimaxWithTimeout(
                    adapter, depth - 1, alpha, beta,
                    !maxPlayer, start, timeLimit,
                    null, rootRepeats, rootDepth
            );

            adapter.undoMove(m);                                  // undo
            currentHash = adapter.computeZobristHash();           // restore hash
            if (child == null) return null;

            int score = child.score;
            if (maxPlayer ? score > bestScore : score < bestScore) {
                bestScore = score;
                bestMove  = m;
            }

            if (maxPlayer) {
                alpha = Math.max(alpha, score);
                if (alpha >= beta) {
                    cutoffCount++;
                    if (depth > 0) recordKiller(m, depth);
                    break;
                }
            } else {
                beta = Math.min(beta, score);
                if (beta <= alpha) {
                    cutoffCount++;
                    if (depth > 0) recordKiller(m, depth);
                    break;
                }
            }
            if (depth > 0) recordHistory(m, depth);
        }

        // Late move reduction / repetition bonus
        if (depth == rootDepth && bestMove != null) {
            adapter.makeMove(bestMove);
            currentHash = adapter.computeZobristHash();
            long nextKey = currentHash;
            adapter.undoMove(bestMove);
            currentHash = adapter.computeZobristHash();

            int repCnt = rootRepeats.getOrDefault(nextKey, 0);
            int staticEval = adapter.evaluate();
            if (repCnt >= 2 && staticEval > 200) {
                bestScore -= MATE_SCORE / 2;
            }
        }

        // Store in transposition table
        TTEntry newEnt = new TTEntry();
        newEnt.depth = depth;
        newEnt.best  = bestMove;
        newEnt.value = bestScore;
        if      (bestScore <= origAlpha) newEnt.flag = 2;
        else if (bestScore >= origBeta)  newEnt.flag = 1;
        else                              newEnt.flag = 0;
        tt.put(key, newEnt);

        return new ScoredMove(bestMove, bestScore);
    }

    private int quiesce(
            BoardAdapter adapter,
            boolean maxPlayer,
            int alpha,
            int beta
    ) {
        int stand = adapter.evaluate();
        if (maxPlayer) {
            if (stand >= beta) return beta;
            alpha = Math.max(alpha, stand);
        } else {
            if (stand <= alpha) return alpha;
            beta = Math.min(beta, stand);
        }

        List<Move> caps = adapter.generateCaptures();
        for (Move m : caps) {
            if (!adapter.makeMove(m)) continue;        // skip illegal captures
            currentHash = adapter.computeZobristHash();

            int score = quiesce(adapter, !maxPlayer, alpha, beta);

            adapter.undoMove(m);
            currentHash = adapter.computeZobristHash();

            if (maxPlayer) {
                if (score >= beta) return beta;
                alpha = Math.max(alpha, score);
            } else {
                if (score <= alpha) return alpha;
                beta = Math.min(beta, score);
            }
        }

        return maxPlayer ? alpha : beta;
    }

    private void recordKiller(Move m,int depth) {
        int d = depth-1;
        killerMoves[1][d] = killerMoves[0][d];
        killerMoves[0][d] = m;
    }
    private void recordHistory(Move m,int depth) {
        if (m.capturedPiece == null)
            historyHeuristic[m.fromIndex()][m.toIndex()] += depth*depth;
    }

    public int evaluateBoard(BitboardBoard bb) {
        long[] S = bb.getRawBoard();
        int score = 0;

        // 1) Material + center-control + PST + castled-king bonus
        for (int sq = 0; sq < 64; sq++) {
            Piece p = BitboardBoard.getPieceAtSquare(sq, S);
            if (p == null) continue;

            int r = sq / 8, c = sq % 8;

            // material + center control
            int v  = getPieceValue(p);
            int cb = CENTER_CONTROL_BONUS[r][c];
            score += p.isWhite() ? v + cb : -(v + cb);

            // piece-square table
            int pieceIdx = switch (p.getClass().getSimpleName().toLowerCase()) {
                case "pawn"   -> p.isWhite() ? PieceSquareTables.WP : PieceSquareTables.BP;
                case "knight" -> p.isWhite() ? PieceSquareTables.WN : PieceSquareTables.BN;
                case "bishop" -> p.isWhite() ? PieceSquareTables.WB : PieceSquareTables.BB;
                case "rook"   -> p.isWhite() ? PieceSquareTables.WR : PieceSquareTables.BR;
                case "queen"  -> p.isWhite() ? PieceSquareTables.WQ : PieceSquareTables.BQ;
                case "king"   -> p.isWhite() ? PieceSquareTables.WK : PieceSquareTables.BK;
                default       -> throw new IllegalStateException("Unknown piece: " + p);
            };
            int pst = PST.getPieceSquareValue(pieceIdx, sq);
            score += pst;

            // little bonus for castled king
            if (p instanceof King) {
                if (p.isWhite() && r == 7 && (c == 2 || c == 6)) score += 30;
                if (!p.isWhite()&& r == 0 && (c == 2 || c == 6)) score -= 30;
            }
        }

        // 2) Endgame bonuses
        int pieceCount = 0;
        int wkSq = Long.numberOfTrailingZeros(S[8]),
                bkSq = Long.numberOfTrailingZeros(S[14]);
        for (int i = 0; i < 64; i++) {
            Piece p = BitboardBoard.getPieceAtSquare(i, S);
            if (p != null && !(p instanceof King)) pieceCount++;
        }

        if (pieceCount <= ENDGAME_PIECE_THRESHOLD) {
            // King centrality
            int wkr = wkSq/8, wkc = wkSq%8,
                    bkr = bkSq/8, bkc = bkSq%8;
            score += KING_ACTIVITY_WEIGHT
                    * (kingCentrality(wkr,wkc) - kingCentrality(bkr,bkc));

            // Mobility
            int[] whiteMoves = BitboardBoard.getAllMoves(S, true);
            int[] blackMoves = BitboardBoard.getAllMoves(S, false);
            score += MOBILITY_WEIGHT * (whiteMoves.length - blackMoves.length);

            // Passed-pawn bonus
            for (int sq = 0; sq < 64; sq++) {
                Piece p = BitboardBoard.getPieceAtSquare(sq, S);
                if (!(p instanceof Pawn)) continue;
                int r = sq/8;
                if (isPassedPawn(sq, S)) {
                    boolean whitePawn = p.isWhite();
                    int dist = whitePawn ? r : (7 - r);
                    score += (whitePawn ? +1 : -1)
                            * PASSED_PAWN_WEIGHT
                            * (8 - dist);
                }
            }
        }

        // 3) Check bonus
        if (BitboardBoard.isInCheck(S, false)) score += CHECK_BONUS;
        if (BitboardBoard.isInCheck(S, true )) score -= CHECK_BONUS;

        return score;
    }


    private boolean isPassedPawn(int sq, long[] S) {
        Piece p = BitboardBoard.getPieceAtSquare(sq, S);
        if (!(p instanceof Pawn)) return false;
        boolean white = p.isWhite();
        int dir = white ? +1 : -1;
        int file = sq % 8;
        long mask = 0L;
        for (int step = 1; step < 8; step++) {
            int r = (sq/8) + dir*step;
            if (r < 0 || r > 7) break;
            for (int df = -1; df <= 1; df++) {
                int f = file + df;
                if (f < 0 || f > 7) continue;
                mask |= 1L << (r*8 + f);
            }
        }
        long enemyPawns = white ? S[9] : S[3];
        return (mask & enemyPawns) == 0;
    }

    private int kingCentrality(int r, int c) {
        double dr = Math.abs(r - 3.5), dc = Math.abs(c - 3.5);
        return (int)(7 - (dr + dc));
    }

    private int getPieceValue(Piece p) {
        return switch(p.getClass().getSimpleName().toLowerCase()) {
            case "pawn"   -> 100;
            case "knight" -> 320;
            case "bishop" -> 330;
            case "rook"   -> 500;
            case "queen"  -> 900;
            case "king"   -> 20000;
            default       -> 0;
        };
    }

    private long computeZobrist(Board board, boolean whiteToMove) {
        long h = whiteToMove ? ZOBRIST_SIDE_TO_MOVE : 0L;
        Piece[][] b = board.board;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c];
                if (p == null) continue;
                int type = switch (p.getClass().getSimpleName().toLowerCase()) {
                    case "pawn"   -> 0;
                    case "knight" -> 1;
                    case "bishop" -> 2;
                    case "rook"   -> 3;
                    case "queen"  -> 4;
                    case "king"   -> 5;
                    default       -> -1;
                };
                if (type < 0) continue;
                int index = (p.isWhite() ? 0 : 6) + type;
                h ^= ZOBRIST_PIECE_KEYS[index][r * 8 + c];
            }
        }
        return h;
    }

    public long zobrist(Board board, boolean whiteToMove) {
        return computeZobrist(board, whiteToMove);
    }

    public List<Move> sortMovesByHeuristics(
            List<Move> moves,
            int depth,
            Move pv,
            BoardAdapter adapter
    ) {
        Map<Move,Integer> scores = new HashMap<>();
        int d = depth > 0 ? depth - 1 : 0;

        for (Move m : moves) {
            int sc = 0;

            // 1) PV bonus
            if (pv != null && m == pv)               sc += PV_BONUS;

            // 2) MVV/LVA
            if (m.capturedPiece != null) {
                sc += 10_000
                        + getPieceValue(m.capturedPiece)
                        - getPieceValue(m.movedPiece);
            }
            // 3) “gives check” bonus
            else {
                // apply the move, test for check, then undo
                if (adapter.makeMove(m)) {
                    if (adapter.isInCheck()) sc += 5_000;
                    adapter.undoMove(m);
                }
            }

            // 4) Killer & History heuristics
            if (depth > 0) {
                if (killerMoves[0][d] == m) sc += KILLER1_BONUS;
                if (killerMoves[1][d] == m) sc += KILLER2_BONUS;
                sc += historyHeuristic[m.fromIndex()][m.toIndex()];
            }

            // 5) Center-control bonus
            sc += CENTER_CONTROL_BONUS[m.toRow][m.toCol];

            scores.put(m, sc);
        }

        // sort descending by score
        moves.sort((a, b) -> scores.get(b) - scores.get(a));
        return moves;
    }
}