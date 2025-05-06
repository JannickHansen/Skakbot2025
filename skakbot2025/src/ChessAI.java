import Board.Board;
import Pieces.Piece;
import java.util.ArrayList;
import java.util.List;

public class ChessAI {

    // depth: 3 = 0.1 - 0.5s
    // depth: 4 = 0.5 - 2s
    // depth: 5 = 3 - 10s
    // depth: 6 = 15s+

    private static final int MAX_DEPTH = 5;
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

    public static class Move {
        public int fromRow, fromCol, toRow, toCol;
        public Piece movedPiece, capturedPiece;

        public Move(int fromRow, int fromCol, int toRow, int toCol, Piece movedPiece, Piece capturedPiece) {
            this.fromRow = fromRow;
            this.fromCol = fromCol;
            this.toRow = toRow;
            this.toCol = toCol;
            this.movedPiece = movedPiece;
            this.capturedPiece = capturedPiece;
        }
    }

    public void calculateAndMakeMoveAsync(Board board, boolean isWhite, Runnable onMoveComplete) {
        new Thread(() -> {
            Move bestMove = getBestMove(board, isWhite);
            if (bestMove != null) {
                makeMove(board, bestMove);
            }
            if (onMoveComplete != null) {
                onMoveComplete.run();
            }
        }).start();
    }

    private Move getBestMove(Board board, boolean isWhite) {
        return minimax(board, MAX_DEPTH, Integer.MIN_VALUE, Integer.MAX_VALUE, isWhite).move;
    }

    private static class ScoredMove {
        Move move;
        int score;

        public ScoredMove(Move move, int score) {
            this.move = move;
            this.score = score;
        }
    }

    private ScoredMove minimax(Board board, int depth, int alpha, int beta, boolean maximizingPlayer) {
        if (depth == 0) {
            int eval = evaluateBoard(board);
            return new ScoredMove(null, eval);
        }

        List<Move> possibleMoves = generateAllMoves(board, maximizingPlayer);

        if (possibleMoves.isEmpty()) {
            int eval = evaluateBoard(board);
            return new ScoredMove(null, eval);
        }

        Move bestMove = null;

        if (maximizingPlayer) {
            int maxEval = Integer.MIN_VALUE;
            for (Move move : possibleMoves) {
                makeMove(board, move);
                int eval = minimax(board, depth - 1, alpha, beta, false).score;
                undoMove(board, move);
                if (eval > maxEval) {
                    maxEval = eval;
                    bestMove = move;
                }
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) break;
            }
            return new ScoredMove(bestMove, maxEval);
        } else {
            int minEval = Integer.MAX_VALUE;
            for (Move move : possibleMoves) {
                makeMove(board, move);
                int eval = minimax(board, depth - 1, alpha, beta, true).score;
                undoMove(board, move);
                if (eval < minEval) {
                    minEval = eval;
                    bestMove = move;
                }
                beta = Math.min(beta, eval);
                if (beta <= alpha) break;
            }
            return new ScoredMove(bestMove, minEval);
        }
    }

    private List<Move> generateAllMoves(Board board, boolean isWhite) {
        List<Move> moves = new ArrayList<>();
        Piece[][] b = board.board;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c];
                if (p != null && p.isWhite() == isWhite) {
                    for (int nr = 0; nr < 8; nr++) {
                        for (int nc = 0; nc < 8; nc++) {
                            if (p.isValidMove(nr, nc, b)) {
                                Piece captured = b[nr][nc];
                                Move move = new Move(r, c, nr, nc, p, captured);
                                makeMove(board, move);
                                if (!board.isInCheck(isWhite)) {
                                    moves.add(move);
                                }
                                undoMove(board, move);
                            }
                        }
                    }
                }
            }
        }
        return moves;
    }

    // Making a large number of Move objects is inefficient, but we can encode the data into a 32-bit integer instead.
    private int encodeMove(int from, int to, int piece, int captured, int promotion, boolean isEP, boolean isCastle) {
        return (from) | (to << 6) | (piece << 12) | (captured << 16) | (promotion << 20) | ((isEP ? 1 : 0) << 24) | ((isCastle ? 1 : 0) << 25);
    }

    // Apparently these are efficient enough that there's no advantage to hardcoding the logic.
    public static int getFrom(int move)       { return move & 0x3F; }  // Bits 0-5
    public static int getTo(int move)         { return (move >>> 6) & 0x3F; }  // Bits 6-11
    public static int getPiece(int move)      { return (move >>> 12) & 0xF; }  // Bits 12-15
    public static int getCaptured(int move)   { return (move >>> 16) & 0xF; }  // Bits 16-19
    public static int getPromotion(int move)  { return (move >>> 20) & 0xF; }  // Bits 20-23
    public static boolean isEnPassant(int move) { return ((move >>> 24) & 1) != 0; }  // Bit 24
    public static boolean isCastling(int move)  { return ((move >>> 25) & 1) != 0; }  // Bit 25

    private void makeMove(Board board, Move move) {
        board.board[move.toRow][move.toCol] = move.movedPiece;
        board.board[move.fromRow][move.fromCol] = null;
        move.movedPiece.setPosition(move.toRow, move.toCol);
    }

    private void undoMove(Board board, Move move) {
        board.board[move.fromRow][move.fromCol] = move.movedPiece;
        board.board[move.toRow][move.toCol] = move.capturedPiece;
        move.movedPiece.setPosition(move.fromRow, move.fromCol);
    }

    private int evaluateBoard(Board board) {
        int score = 0;
        Piece[][] b = board.board;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = b[r][c];
                if (p != null) {
                    int pieceValue = getPieceValue(p);
                    int centerBonus = CENTER_CONTROL_BONUS[r][c];
                    score += pieceValue + (p.isWhite() ? centerBonus : -centerBonus);
                }
            }
        }
        return score;
    }

    private int getPieceValue(Piece piece) {
        int value = switch (piece.getClass().getSimpleName().toLowerCase()) {
            case "pawn"   -> 100;
            case "knight" -> 320;
            case "bishop" -> 330;
            case "rook"   -> 500;
            case "queen"  -> 900;
            case "king"   -> 20000;
            default -> 0;
        };
        return piece.isWhite() ? value : -value;
    }
}
