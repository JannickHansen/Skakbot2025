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

    private boolean inBounds(int r, int c) {
        return r >= 0 && r < 8 && c >= 0 && c < 8;
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

        public Move(int fr, int fc, int tr, int tc, Piece m, Piece c) {
            this(fr,fc,tr,tc,m,c,null);
        }

        public int fromIndex() { return fromRow*8 + fromCol; }
        public int toIndex()   { return toRow*8   + toCol;   }
    }

    public void calculateAndMakeMoveAsync(Board board, boolean isWhite, Runnable onMoveComplete) {
        new Thread(() -> {
            initializeHash(board, isWhite);

            Map<Long,Integer> rootRepeats = new HashMap<>();
            rootRepeats.put(currentHash, 1);

            Move best = getBestMoveWithTimeout(board, isWhite, TIME_LIMIT, rootRepeats);
            boolean moved = false;

            if (best != null) {
                moved = makeMove(board, best, isWhite);
                if (moved) {
                    System.out.println("α/β cutoffs: " + cutoffCount +
                            ", Depth finished: " + lastDepthReached);
                } else {
                    System.err.println("⚠️ Failed to apply best move: " + best);
                }
            }

            if (!moved) {
                List<Move> legal = generateAllMoves(board, isWhite, 1, null);
                if (!legal.isEmpty()) {
                    Move fallback = legal.get(0);
                    makeMove(board, fallback, isWhite);
                    System.out.println("✳️ Fallback move played: " + fallback);
                } else {
                    System.out.println("♟ No legal moves available. Game over.");
                }
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
        if (depth < 0) depth = 0;

        boolean winningMaterial = evaluateBoard(board) * (maxPlayer ? 1 : -1) >= 1800;
        if (depth == 0 && !(winningMaterial || board.isInCheck(maxPlayer))) {
            int q = quiesce(board, maxPlayer, alpha, beta);
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
            int cnt = rootRepeats.getOrDefault(key, 0);
            rootRepeats.put(key, cnt + 1);
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
                    if (depth > 0) {
                        recordKiller(m, depth);
                    }
                    break;
                }
                if (depth > 0) recordHistory(m, depth);
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
                    if (depth > 0) {
                        recordKiller(m, depth);
                    }
                    break;
                }
                if (depth > 0) recordHistory(m, depth);
            }
        }

        if (depth == rootDepth && bestMove != null) {
            makeMove(board, bestMove, maxPlayer);
            long nextKey = currentHash;
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
        List<Move> caps = generateCaptures(board, maxPlayer);
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

    public List<Move> generateAllMoves(Board board, boolean isWhite, int depth, Move pv) {
        List<Move> moves = new ArrayList<>();
        // 1) generate raw moves
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board.board[r][c];
                if (p == null || p.isWhite() != isWhite) continue;
                if (p instanceof Pawn) {
                    addPawnMoves(board, (Pawn)p, moves);
                } else if (p instanceof Knight) {
                    addJumpMoves(board, p, moves, KNIGHT_JUMPS);
                } else if (p instanceof Bishop) {
                    addSlideMoves(board, p, moves, BISHOP_DIRS);
                } else if (p instanceof Rook) {
                    addSlideMoves(board, p, moves, ROOK_DIRS);
                } else if (p instanceof Queen) {
                    addSlideMoves(board, p, moves, QUEEN_DIRS);
                } else if (p instanceof King) {
                    addJumpMoves(board, p, moves, KING_STEPS);
                    addCastleMoves(board, (King)p, moves);
                }
            }
        }

        // 2) score & sort exactly as before
        Map<Move,Integer> scores = new HashMap<>();
        for (Move m : moves) {
            int sc = 0;
            // PV bonus
            if (pv != null && m == pv) sc += PV_BONUS;
            // MVV/LVA or check bonus
            if (m.capturedPiece != null) {
                sc += 10_000 + getPieceValue(m.capturedPiece) - getPieceValue(m.movedPiece);
            } else if (board.isInCheck(!isWhite)) {
                sc += 5_000;
            }
            // killer & history
            if (depth > 0) {
                int d = depth - 1;
                if (killerMoves[0][d] == m) sc += KILLER1_BONUS;
                if (killerMoves[1][d] == m) sc += KILLER2_BONUS;
                sc += historyHeuristic[m.fromIndex()][m.toIndex()];
            }
            // center control
            sc += CENTER_CONTROL_BONUS[m.toRow][m.toCol];
            scores.put(m, sc);
        }
        moves.sort((m1,m2) -> scores.get(m2) - scores.get(m1));
        return moves;
    }

    private void addPawnMoves(Board board, Pawn p, List<Move> moves) {
        int r = p.getRow(), c = p.getCol(), dir = p.isWhite() ? -1 : +1;
        int tr = r + dir;
        // single push
        if (inBounds(tr,c) && board.board[tr][c] == null) {
            addPawnPromo(p, r, c, tr, c, null, moves);
            // double
            if ((p.isWhite() && r == 6 || !p.isWhite() && r == 1)
                    && board.board[r + 2*dir][c] == null) {
                moves.add(new Move(r, c, r + 2*dir, c, p, null));
            }
        }
        // captures
        for (int dc = -1; dc <= 1; dc += 2) {
            int tc = c + dc;
            if (!inBounds(tr,tc)) continue;
            Piece occ = board.board[tr][tc];
            if (occ != null && occ.isWhite() != p.isWhite()) {
                addPawnPromo(p, r, c, tr, tc, occ, moves);
            }
        }
    }
    private void addPawnPromo(Pawn p, int fr, int fc, int tr, int tc,
                              Piece cap, List<Move> moves) {
        if (tr == 0 || tr == 7) {
            moves.add(new Move(fr,fc,tr,tc,p,cap,new Queen (p.isWhite(),tr,tc)));
            moves.add(new Move(fr,fc,tr,tc,p,cap,new Rook  (p.isWhite(),tr,tc)));
            moves.add(new Move(fr,fc,tr,tc,p,cap,new Bishop(p.isWhite(),tr,tc)));
            moves.add(new Move(fr,fc,tr,tc,p,cap,new Knight(p.isWhite(),tr,tc)));
        } else {
            moves.add(new Move(fr,fc,tr,tc,p,cap));
        }
    }

    private void addJumpMoves(Board board, Piece p,
                              List<Move> moves, int[][] jumps) {
        int r = p.getRow(), c = p.getCol();
        for (int[] d : jumps) {
            int tr = r + d[0], tc = c + d[1];
            if (!inBounds(tr,tc)) continue;
            Piece occ = board.board[tr][tc];
            if (occ == null || occ.isWhite() != p.isWhite()) {
                moves.add(new Move(r, c, tr, tc, p, occ));
            }
        }
    }

    private void addSlideMoves(Board board, Piece p,
                               List<Move> moves, int[][] dirs) {
        int r = p.getRow(), c = p.getCol();
        for (int[] d : dirs) {
            for (int tr = r + d[0], tc = c + d[1];
                 inBounds(tr,tc);
                 tr += d[0], tc += d[1]) {
                Piece occ = board.board[tr][tc];
                if (occ == null) {
                    moves.add(new Move(r, c, tr, tc, p, null));
                } else {
                    if (occ.isWhite() != p.isWhite()) {
                        moves.add(new Move(r, c, tr, tc, p, occ));
                    }
                    break;
                }
            }
        }
    }

    public List<Move> generateCaptures(Board board, boolean isWhite) {
        List<Move> caps = new ArrayList<>();
        for (Move m : generateAllMoves(board, isWhite, 0, null)) {
            if (m.capturedPiece != null) caps.add(m);
        }
        return caps;
    }


    private void addCastleMoves(Board board, King k, List<Move> moves) {
        boolean w = k.isWhite();
        int row = w ? 7 : 0;
        if (board.canCastle(w, +1)) moves.add(new Move(row,4,row,6,k,null));
        if (board.canCastle(w, -1)) moves.add(new Move(row,4,row,2,k,null));
    }

    public void initializeHash(Board board, boolean whiteToMove) {
        currentHash = computeZobrist(board, whiteToMove);
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

    private int evaluateBoard(Board board) {
        int score = 0;
        Piece[][] b = board.board;
        int wkR = 0, wkC = 0, bkR = 0, bkC = 0, pieceCount = 0;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c];
                if (p == null) continue;
                int v  = getPieceValue(p);
                int cb = CENTER_CONTROL_BONUS[r][c];
                score += p.isWhite() ? v + cb : -(v + cb);

                if (p instanceof King) {
                    if (p.isWhite()) {
                        if (r == 7 && (c == 6 || c == 2)) score += 30;
                    } else {
                        if (r == 0 && (c == 6 || c == 2)) score -= 30;
                    }
                }
                if (!(p instanceof Pieces.King)) pieceCount++;
                if (p instanceof Pieces.King) {
                    if (p.isWhite()) { wkR = r; wkC = c; }
                    else             { bkR = r; bkC = c; }
                }
            }
        }

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
                    if (!(p instanceof Pawn)) continue;
                    if (isPassedPawn(b, r, c)) {
                        boolean whitePawn = p.isWhite();
                        int dist = whitePawn ? r : (7 - r);
                        score += (whitePawn ? +1 : -1)
                                * PASSED_PAWN_WEIGHT
                                * (8 - dist);
                    }
                }
            }
        }

        if (board.isInCheck(false)) score += CHECK_BONUS;
        if (board.isInCheck(true))  score -= CHECK_BONUS;

        return score;
    }

    private boolean isPassedPawn(Piece[][] board, int r, int c) {
        Piece p = board[r][c];
        if (!(p instanceof Pawn)) return false;
        boolean whitePawn = p.isWhite();
        int dir = whitePawn ? -1 : +1;
        for (int rr = r + dir; rr >= 0 && rr < 8; rr += dir) {
            for (int fc = c - 1; fc <= c + 1; fc++) {
                if (fc < 0 || fc > 7) continue;
                Piece q = board[rr][fc];
                if (q instanceof Pawn && q.isWhite() != whitePawn) {
                    return false;
                }
            }
        }
        return true;
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