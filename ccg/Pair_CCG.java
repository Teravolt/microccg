package ai.ccg;

public class Pair_CCG<T1,T2> {
	public T1 m_a;
	public T2 m_b;
	
	public Pair_CCG(T1 a, T2 b) {
		m_a = a;
		m_b = b;
	}   
        
	public String toString() {
		return "<" + m_a + "," + m_b + ">";
	}

	@Override
	public boolean equals(Object obj) {
		if ( obj instanceof Pair_CCG ) {
			Pair_CCG<T1,T2> tmp = (Pair_CCG)(obj);
			return tmp.m_a.equals(m_a) && tmp.m_b.equals(m_b);
		}
		return false;
	}
}
