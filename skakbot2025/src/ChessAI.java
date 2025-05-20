import Board.Board;
import Evaluation.SimpleEvaluation;
import Board.BitboardBoard;
import Pieces.Piece;

import java.util.*;

public class ChessAI {
    private static final int MAX_DEPTH =30;
    private static final long TIME_LIMIT = 14_950_000_000L;
    // Value set to attempt early cutoffs by not starting at +-∞
    private static final int ASPIRATION_MARGIN = 1000;
    private static final int MATE_SCORE = 1_000_000;
    private long currentHash;
    private int totalMovesEvaluated = 0;
    private int cutoffsMade = 0;

    // A Signature which points at the random 64-bit Zobrist hash keys for each of the 16 bitboard slices
    private static final long[] ZOBRIST_KEYS = new long[16];
    static {
        Random rnd = new Random(0xDEADBEEFL);
        for (int i = 0; i < 16; i++) {
            ZOBRIST_KEYS[i] = rnd.nextLong();
        }
    }

    private static boolean tablesInitialized = false;
    private static synchronized void ensureLookupTables() {
        if (!tablesInitialized) {
            new BitboardBoard().generateLookupTables();
            tablesInitialized = true;
        }
    }

    private static class TranspositionTableEntry {
        int depth, value, flag;
        int bestMove;
    }
    private final Map<Long,TranspositionTableEntry> TRANSPOSITION_TABLE = new HashMap<>();
    private final SimpleEvaluation evaluator = new SimpleEvaluation();
    private long[] boardState;

    public void startSearchThread(Board board, boolean isWhite, Runnable onMoveComplete) {
        new Thread(() -> {
            // ensure our sliding‐piece tables are built once
            ensureLookupTables();

            // Reset assist counters
            totalMovesEvaluated = 0;
            cutoffsMade        = 0;

            // pack the GUI Board into our 16‐slice bitboard
            boardState  = boardToBitboard(board, isWhite);
            currentHash = computeZobrist(boardState);
            TRANSPOSITION_TABLE.clear();

            // run iterative minimax search with timer applied
            Map<Long,Integer> rootRepeats = new HashMap<>();
            rootRepeats.put(currentHash, 1);
            int bestEnc = iterativeDeepeningSearch(boardState, isWhite, TIME_LIMIT, rootRepeats);

            // If a move was found, apply it to the bitboard, convert back to 2D, and update the GUI.
            if (bestEnc != -1) {
                boardState = BitboardBoard.makeOrUndoMove(boardState, bestEnc);
                Board newBoard = BitboardBoard.bitboardToBoard(boardState);
                board.board   = newBoard.board;
            }

            // informational printout to devs to evaluate quality (happens only at the end, will not affect algoritmn speed)
            System.out.println("Total moves evaluated: " + totalMovesEvaluated);
            System.out.println("Cutoffs made: " + cutoffsMade);

            // let the GUI know we’re done.
            if (onMoveComplete != null) onMoveComplete.run();
        }).start();
    }

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

    private int iterativeDeepeningSearch(long[] state, boolean isWhite, long timeLimit, Map<Long,Integer> rootRepeats) {
        long start = System.nanoTime();
        int lastScore = 0, bestMove = -1;
        int bestDepth = 0;

        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            if (System.nanoTime() - start > timeLimit) break;

            // Apply ASPIRATION_MARGIN
            int alpha = (depth == 1 ? Integer.MIN_VALUE : lastScore - ASPIRATION_MARGIN);
            int beta  = (depth == 1 ? Integer.MAX_VALUE : lastScore + ASPIRATION_MARGIN);

            ScoredMove sm = minimax(state, depth, alpha, beta, isWhite, start, timeLimit, rootRepeats, depth);
            if (sm == null) break;  // timed out

            // Default to +-∞ if ASPIRATION_MARGIN does not find anything
            if (sm.score <= alpha || sm.score >= beta) {
                sm = minimax(state, depth, Integer.MIN_VALUE, Integer.MAX_VALUE,
                        isWhite, start, timeLimit, rootRepeats, depth);
                if (sm == null) break;
            }

            bestDepth = depth;
            lastScore = sm.score;
            bestMove  = sm.move;
        }

        System.out.println("Selected move depth: " + bestDepth);
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
            totalMovesEvaluated++;
            int stand = evaluator.simpleEvaluation(state);
            return new ScoredMove(-1, stand);
        }

        TranspositionTableEntry ent = TRANSPOSITION_TABLE.get(currentHash);
        if (ent != null && ent.depth >= depth) {
            if (ent.flag == 0) return new ScoredMove(ent.bestMove, ent.value);
            if (ent.flag == 1) alpha = Math.max(alpha, ent.value);
            if (ent.flag == 2) beta  = Math.min(beta, ent.value);
            if (alpha >= beta) {
                cutoffsMade++;
                return new ScoredMove(ent.bestMove, ent.value);
            }
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
            // make the move
            long oldHash = currentHash;
            BitboardBoard.makeOrUndoMove(state, m);
            currentHash = computeZobrist(state);

            // search the child
            ScoredMove child = minimax(
                    state, depth-1, alpha, beta,
                    !maxPlayer, start, timeLimit,
                    rootRepeats, rootDepth
            );

            // undo the move
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
        TranspositionTableEntry ne = new TranspositionTableEntry();
        ne.depth    = depth;
        ne.value    = bestScore;
        ne.bestMove = bestMove;
        ne.flag     = ( bestScore <= alpha ? 2 :
                bestScore >= beta  ? 1 : 0 );
        TRANSPOSITION_TABLE.put(currentHash, ne);

        return new ScoredMove(bestMove, bestScore);
    }

    private long computeZobrist(long[] state) {
        long h = 0;
        for (int i = 0; i < 16; i++) {
            h ^= state[i] * ZOBRIST_KEYS[i];
        }
        return h;
    }
}