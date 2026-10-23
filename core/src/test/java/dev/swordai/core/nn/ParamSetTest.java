package dev.swordai.core.nn;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParamSetTest {
	private static final int COLS = 3;

	/** A layer with {@code rows} inputs whose weights are numbered row * 10 + column, plus a bias. */
	private static ParamSet layer(int rows, boolean numbered) {
		ParamSet ps = new ParamSet();
		Param w = ps.add("layer.w", rows * COLS);
		Param b = ps.add("layer.b", COLS);
		if (numbered) {
			for (int r = 0; r < rows; r++) {
				for (int c = 0; c < COLS; c++) {
					w.w[r * COLS + c] = r * 10 + c + 1;
					w.m[r * COLS + c] = -(r * 10 + c + 1);
					w.v[r * COLS + c] = 0.5f * (r * 10 + c + 1);
				}
			}
			b.w[0] = 7f;
			b.w[2] = 9f;
			ps.adamSteps = 42;
		}
		return ps;
	}

	private static DataInputStream saved(ParamSet ps) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ps.write(new DataOutputStream(bytes));
		return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
	}

	@Test
	void roundTripsExactly() throws IOException {
		ParamSet a = layer(4, true);
		ParamSet b = layer(4, false);
		b.read(saved(a));
		for (int i = 0; i < a.params().size(); i++) {
			for (int j = 0; j < a.params().get(i).w.length; j++) {
				assertEquals(a.params().get(i).w[j], b.params().get(i).w[j]);
				assertEquals(a.params().get(i).m[j], b.params().get(i).m[j]);
				assertEquals(a.params().get(i).v[j], b.params().get(i).v[j]);
			}
		}
		assertEquals(42, b.adamSteps);
	}

	@Test
	void olderLayerGainsZeroRowsForTheInputsAddedSince() throws IOException {
		// Saved with 4 inputs; two more were later inserted after the first one.
		ParamSet old = layer(4, true);
		ParamSet now = layer(6, false);
		now.read(saved(old), 1, 2);

		Param w = now.params().get(0);
		int[] sourceRow = {0, -1, -1, 1, 2, 3};
		for (int r = 0; r < 6; r++) {
			for (int c = 0; c < COLS; c++) {
				float expected = sourceRow[r] < 0 ? 0f : sourceRow[r] * 10 + c + 1;
				// A zero tolerance compares by value, so the zero rows match whatever the sign of zero.
				assertEquals(expected, w.w[r * COLS + c], 0f, "weight row " + r + " col " + c);
				assertEquals(-expected, w.m[r * COLS + c], 0f, "first moment row " + r);
				assertEquals(0.5f * expected, w.v[r * COLS + c], 0f, "second moment row " + r);
			}
		}
		assertEquals(7f, now.params().get(1).w[0], "tensors of unchanged size load as they are");
		assertEquals(9f, now.params().get(1).w[2]);
		assertEquals(42, now.adamSteps);
	}

	@Test
	void aShapeThatIsNotJustAddedInputsIsRejected() throws IOException {
		ParamSet old = layer(4, true);
		assertThrows(IOException.class, () -> layer(6, false).read(saved(old)), "strict load must not guess");
		assertThrows(IOException.class, () -> layer(6, false).read(saved(old), 1, 5), "row count does not add up");
		assertThrows(IOException.class, () -> layer(3, false).read(saved(old), 1, 2), "a smaller network is not an upgrade");
	}
}
