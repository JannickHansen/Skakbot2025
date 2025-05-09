import Board.Board;
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
    private static final long TIME_LIMIT = 14_950_000_000L; // 15s
    private static final int ASPIRATION_WINDOW_VALUE = 100; // ±100cp

    // “Mate” terminal score
    private static final int MATE_SCORE = 1_000_000;

    // Center‐control bonus
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

    // Endgame‐phase constants
    private static final int ENDGAME_PIECE_THRESHOLD = 8;
    private static final int KING_ACTIVITY_WEIGHT    = 20;
    private static final int MOBILITY_WEIGHT         = 4;
    private static final int PASSED_PAWN_WEIGHT      = 20;

    // Move‐ordering bonuses
    private static final int PV_BONUS      = 1_000_000;
    private static final int KILLER1_BONUS =   8_000;
    private static final int KILLER2_BONUS =   7_000;

    // Zobrist hashing
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

    // Transposition table entry
    private static class TTEntry {
        int depth, value, flag;
        Move best;
        // flag: 0=EXACT, 1=LOWERBOUND, 2=UPPERBOUND
    }
    private final Map<Long,TTEntry> tt = new HashMap<>();

    private final Move[][] killerMoves       = new Move[2][MAX_DEPTH];
    private final int[][]   historyHeuristic = new int[64][64];

    private long cutoffCount      = 0;
    private int  lastDepthReached = 0;

    public static class Move {
        public final int fromRow, fromCol, toRow, toCol;
        public final Piece movedPiece, capturedPiece;
        public final Piece promotion;  // null if no promotion

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
            Map<Long,Integer> rootRepeats = new HashMap<>();
            long rootKey = computeZobrist(board, isWhite);
            rootRepeats.put(rootKey, 1);

            Move best = getBestMoveWithTimeout(board, isWhite, TIME_LIMIT, rootRepeats);
            if (best != null) {
                makeMove(board, best, isWhite);
                System.out.println("α/β cutoffs: " + cutoffCount +
                        ", Depth finished: " + lastDepthReached);
            }
            if (onMoveComplete != null) onMoveComplete.run();
        }).start();
    }

    private Move getBestMoveWithTimeout(
            Board board,
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

            ScoredMove sm = minimaxWithTimeout(
                    board, depth, alpha, beta, isWhite,
                    start, timeLimit, prevPV, rootRepeats, depth
            );

            // aspiration fallback
            if (sm != null && (sm.score <= alpha || sm.score >= beta)) {
                sm = minimaxWithTimeout(
                        board, depth,
                        Integer.MIN_VALUE, Integer.MAX_VALUE,
                        isWhite, start, timeLimit,
                        prevPV, rootRepeats, depth
                );
            }
            if (sm == null) break;

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
            Board board,
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

        long key = computeZobrist(board, maxPlayer);
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
            int cnt = rootRepeats.getOrDefault(key, 0);
            rootRepeats.put(key, cnt + 1);
        }

        if (depth == 0) {
            int q = quiesce(board, maxPlayer, alpha, beta);
            return new ScoredMove(null, q);
        }

        int origAlpha = alpha, origBeta = beta;
        List<Move> moves = generateAllMoves(board, maxPlayer, depth, pv);

        if (moves.isEmpty()) {
            if (board.isCheckmate(maxPlayer)) {
                int v = maxPlayer ? -MATE_SCORE : +MATE_SCORE;
                return new ScoredMove(null, v);
            } else {
                return new ScoredMove(null, 0);
            }
        }

        Move bestMove = null;
        int  bestScore = maxPlayer ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        if (maxPlayer) {
            for (Move m : moves) {
                if (System.nanoTime() - start >= timeLimit) return null;
                makeMove(board, m, true);
                ScoredMove child = minimaxWithTimeout(
                        board, depth-1, alpha, beta,
                        false, start, timeLimit,
                        null, rootRepeats, rootDepth
                );
                undoMove(board, m);
                if (child == null) return null;

                int score = child.score;
                if (score > bestScore) {
                    bestScore = score; bestMove = m;
                }
                alpha = Math.max(alpha, score);
                if (beta <= alpha) {
                    cutoffCount++;
                    recordKiller(m, depth);
                    break;
                }
                recordHistory(m, depth);
            }
        } else {
            for (Move m : moves) {
                if (System.nanoTime() - start >= timeLimit) return null;
                makeMove(board, m, false);
                ScoredMove child = minimaxWithTimeout(
                        board, depth-1, alpha, beta,
                        true, start, timeLimit,
                        null, rootRepeats, rootDepth
                );
                undoMove(board, m);
                if (child == null) return null;

                int score = child.score;
                if (score < bestScore) {
                    bestScore = score; bestMove = m;
                }
                beta = Math.min(beta, score);
                if (beta <= alpha) {
                    cutoffCount++;
                    recordKiller(m, depth);
                    break;
                }
                recordHistory(m, depth);
            }
        }

        if (depth == rootDepth && bestMove != null) {
            makeMove(board, bestMove, maxPlayer);
            long nextKey = computeZobrist(board, !maxPlayer);
            undoMove(board, bestMove);
            int repCnt = rootRepeats.getOrDefault(nextKey, 0);
            int staticEval = evaluateBoard(board) * (maxPlayer ? +1 : -1);
            if (repCnt >= 2 && staticEval > 200) {
                bestScore -= MATE_SCORE/2;
            }
        }

        TTEntry newEnt = new TTEntry();
        newEnt.depth = depth;
        newEnt.best  = bestMove;
        newEnt.value = bestScore;
        if (bestScore <= origAlpha)      newEnt.flag = 2;
        else if (bestScore >= origBeta)  newEnt.flag = 1;
        else                              newEnt.flag = 0;
        tt.put(key, newEnt);

        return new ScoredMove(bestMove, bestScore);
    }

    private int quiesce(Board board, boolean maxPlayer, int alpha, int beta) {
        int stand = evaluateBoard(board);
        if (maxPlayer) {
            if (stand >= beta) return beta;
            alpha = Math.max(alpha, stand);
        } else {
            if (stand <= alpha) return alpha;
            beta = Math.min(beta, stand);
        }

        List<Move> caps = new ArrayList<>();
        Piece[][] arr = board.board;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = arr[r][c];
                if (p == null || p.isWhite() != maxPlayer) continue;
                for (int tr = 0; tr < 8; tr++) {
                    for (int tc = 0; tc < 8; tc++) {
                        if (!p.isValidMove(tr, tc, arr)) continue;
                        if (arr[tr][tc] == null) continue;
                        Move m = new Move(r, c, tr, tc, p, arr[tr][tc]);
                        if (!makeMove(board, m, maxPlayer)) continue;
                        caps.add(m);
                        undoMove(board, m);
                    }
                }
            }
        }

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = arr[r][c];
                if (p == null || p.isWhite() != maxPlayer) continue;
                for (int tr = 0; tr < 8; tr++) {
                    for (int tc = 0; tc < 8; tc++) {
                        if (!p.isValidMove(tr, tc, arr)) continue;
                        if (arr[tr][tc] != null) continue;
                        Move m = new Move(r, c, tr, tc, p, null);
                        if (!makeMove(board, m, maxPlayer)) continue;
                        if (board.isInCheck(!maxPlayer)) {
                            caps.add(m);
                        }
                        undoMove(board, m);
                    }
                }
            }
        }

        caps.sort((m1, m2) -> {
            int cap2 = (m2.capturedPiece != null ? getPieceValue(m2.capturedPiece) : 0);
            int mov2 = (m2.movedPiece      != null ? getPieceValue(m2.movedPiece)      : 0);
            int cap1 = (m1.capturedPiece != null ? getPieceValue(m1.capturedPiece) : 0);
            int mov1 = (m1.movedPiece      != null ? getPieceValue(m1.movedPiece)      : 0);
            return (cap2 - mov2) - (cap1 - mov1);
        });

        for (Move m : caps) {
            makeMove(board, m, maxPlayer);
            int score = quiesce(board, !maxPlayer, alpha, beta);
            undoMove(board, m);
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

    private long computeZobrist(Board board, boolean whiteToMove) {
        long h = whiteToMove ? ZOBRIST_SIDE_TO_MOVE : 0L;
        Piece[][] b = board.board;
        for (int r=0;r<8;r++) for (int c=0;c<8;c++){
            Piece p = b[r][c];
            if (p==null) continue;
            int type = switch(p.getClass().getSimpleName().toLowerCase()){
                case "pawn"->0; case "knight"->1; case "bishop"->2;
                case "rook"->3; case "queen"->4; case "king"->5;
                default->-1;
            };
            if (type<0) continue;
            int off = p.isWhite()?0:6;
            h ^= ZOBRIST_PIECE_KEYS[off+type][r*8+c];
        }
        return h;
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

    public List<Move> generateAllMoves(
            Board board, boolean isWhite, int depth, Move pv
    ) {
        List<Move> moves = new ArrayList<>();
        Map<Move,Integer> scores = new HashMap<>();
        Piece[][] arr = board.board;

        for (int r=0;r<8;r++) for (int c=0;c<8;c++){
            Piece p = arr[r][c];
            if (p==null || p.isWhite()!=isWhite) continue;

            // Generate normal and promotion moves
            for (int tr=0;tr<8;tr++) for (int tc=0;tc<8;tc++){
                if (!p.isValidMove(tr,tc,arr)) continue;
                Piece cap = arr[tr][tc];

                // Pawn promotions
                if (p instanceof Pawn && (tr == 0 || tr == 7)) {
                    Piece[] promos = {
                            new Queen(isWhite, tr, tc),
                            new Rook (isWhite, tr, tc),
                            new Bishop(isWhite, tr, tc),
                            new Knight(isWhite, tr, tc)
                    };
                    for (Piece promo : promos) {
                        Move m = new Move(r, c, tr, tc, p, cap, promo);
                        if (!makeMove(board, m, isWhite)) continue;
                        int sc = 0;
                        if (pv != null && m == pv) sc += PV_BONUS;
                        if (cap != null)
                            sc += 10_000 + getPieceValue(cap) - getPieceValue(p);
                        else if (board.isInCheck(!isWhite))
                            sc += 5_000;
                        int d = depth - 1;
                        if (killerMoves[0][d] == m) sc += KILLER1_BONUS;
                        if (killerMoves[1][d] == m) sc += KILLER2_BONUS;
                        sc += historyHeuristic[m.fromIndex()][m.toIndex()];
                        sc += CENTER_CONTROL_BONUS[tr][tc];
                        moves.add(m);
                        scores.put(m, sc);
                        undoMove(board, m);
                    }
                    continue;
                }

                // Normal moves
                Move m = new Move(r, c, tr, tc, p, cap);
                if (!makeMove(board, m, isWhite)) continue;
                int sc = 0;
                if (pv != null && m == pv) sc += PV_BONUS;
                if (cap != null)
                    sc += 10_000 + getPieceValue(cap) - getPieceValue(p);
                else if (board.isInCheck(!isWhite))
                    sc += 5_000;
                int d = depth - 1;
                if (killerMoves[0][d] == m) sc += KILLER1_BONUS;
                if (killerMoves[1][d] == m) sc += KILLER2_BONUS;
                sc += historyHeuristic[m.fromIndex()][m.toIndex()];
                sc += CENTER_CONTROL_BONUS[tr][tc];
                moves.add(m);
                scores.put(m, sc);
                undoMove(board, m);
            }

            // Castling moves
            if (p instanceof King) {
                // king-side
                if (board.canCastle(isWhite, +1)) {
                    Move m = new Move(r, c, r, c + 2, p, null);
                    if (makeMove(board, m, isWhite)) {
                        int sc = PV_BONUS;
                        moves.add(m);
                        scores.put(m, sc);
                        undoMove(board, m);
                    }
                }
                // queen-side
                if (board.canCastle(isWhite, -1)) {
                    Move m = new Move(r, c, r, c - 2, p, null);
                    if (makeMove(board, m, isWhite)) {
                        int sc = PV_BONUS;
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

    private boolean makeMove(Board board, Move m, boolean isWhite) {
        if (!m.movedPiece.isValidMove(m.toRow, m.toCol, board.board))
            return false;

        // Perform the move (or promotion)
        if (m.promotion != null) {
            board.board[m.toRow][m.toCol] = m.promotion;
        } else {
            board.board[m.toRow][m.toCol] = m.movedPiece;
        }
        board.board[m.fromRow][m.fromCol] = null;

        if (m.promotion == null) {
            m.movedPiece.setPosition(m.toRow, m.toCol);
        }

        // Handle castling rook move
        if (m.movedPiece instanceof King && Math.abs(m.toCol - m.fromCol) == 2) {
            int dir = (m.toCol > m.fromCol) ? 1 : -1;
            int rookFrom = (dir == 1 ? 7 : 0);
            int rookTo   = m.fromCol + dir;
            Piece rook   = board.board[m.toRow][rookFrom];
            board.board[m.toRow][rookTo]   = rook;
            board.board[m.toRow][rookFrom] = null;
            rook.setPosition(m.toRow, rookTo);
        }

        // If move leaves own king in check, undo and reject
        if (board.isInCheck(isWhite)) {
            undoMove(board, m);
            return false;
        }
        return true;
    }

    private void undoMove(Board board, Move m) {
        // Undo castling rook
        if (m.movedPiece instanceof King && Math.abs(m.toCol - m.fromCol) == 2) {
            int dir = (m.toCol > m.fromCol) ? 1 : -1;
            int rookTo   = m.fromCol + dir;
            int rookFrom = (dir == 1 ? 7 : 0);
            Piece rook   = board.board[m.toRow][rookTo];
            board.board[m.toRow][rookFrom] = rook;
            rook.setPosition(m.toRow, rookFrom);
            board.board[m.toRow][rookTo]   = null;
        }

        // Restore moving piece or pawn (if promotion)
        if (m.promotion != null) {
            board.board[m.fromRow][m.fromCol] = m.movedPiece;
            m.movedPiece.setPosition(m.fromRow, m.fromCol);
        } else {
            board.board[m.fromRow][m.fromCol] = m.movedPiece;
            m.movedPiece.setPosition(m.fromRow, m.fromCol);
        }

        // Restore captured piece
        board.board[m.toRow][m.toCol] = m.capturedPiece;
    }

    private int evaluateBoard(Board board) {
        int score = 0;
        Piece[][] b = board.board;
        int wkR = 0, wkC = 0, bkR = 0, bkC = 0, pieceCount = 0;

        // 1) Material + center + castling reward
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c];
                if (p == null) continue;

                int v  = getPieceValue(p);
                int cb = CENTER_CONTROL_BONUS[r][c];
                score += p.isWhite() ? v + cb : -(v + cb);

                // ─── NEW: castle‐safety bonus ───
                if (p instanceof King) {
                    if (p.isWhite()) {
                        // white castled positions g1 (7,6) or c1 (7,2)
                        if (r == 7 && (c == 6 || c == 2)) score += 30;
                    } else {
                        // black castled positions g8 (0,6) or c8 (0,2)
                        if (r == 0 && (c == 6 || c == 2)) score -= 30;
                    }
                }

                // track non-king pieces and locate kings
                if (!(p instanceof Pieces.King)) pieceCount++;
                if (p instanceof Pieces.King) {
                    if (p.isWhite()) { wkR = r; wkC = c; }
                    else             { bkR = r; bkC = c; }
                }
            }
        }

        // 2) Endgame‐phase bonuses (king activity, mobility, passed‐pawn)
        if (pieceCount <= ENDGAME_PIECE_THRESHOLD) {
            int wKA = kingCentrality(wkR, wkC),
                    bKA = kingCentrality(bkR, bkC);
            score += KING_ACTIVITY_WEIGHT * (wKA - bKA);

            int wm = generateAllMoves(board, true, 1, null).size(),
                    bm = generateAllMoves(board, false,1, null).size();
            score += MOBILITY_WEIGHT * (wm - bm);

            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    Piece p = b[r][c];
                    if (!(p instanceof Pieces.Pawn)) continue;
                    boolean whitePawn = p.isWhite(), passed = true;
                    int dir = whitePawn ? -1 : +1;
                    for (int rr = r + dir; rr >= 0 && rr < 8; rr += dir) {
                        if (b[rr][c] != null && b[rr][c].isWhite() == whitePawn) {
                            passed = false;
                            break;
                        }
                    }
                    if (passed) {
                        int dist = whitePawn ? r : 7 - r;
                        score += (whitePawn ? 1 : -1)
                                * PASSED_PAWN_WEIGHT
                                * (8 - dist);
                    }
                }
            }
        }

        return score;
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
}
