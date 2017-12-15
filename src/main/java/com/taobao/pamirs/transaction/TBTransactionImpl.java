package com.taobao.pamirs.transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 *
 * @author xuannan
 *
 */
public class TBTransactionImpl {
	private static transient Log log = LogFactory
			.getLog(TBTransactionImpl.class);
	protected static List<TBTransactionImpl> m_LeaveTransaction = Collections.synchronizedList(new ArrayList<TBTransactionImpl>());
	protected int timeOut = -1;
	protected int warnTime = 1000;//���񱨾�����
	boolean m_isCommitError = false;
	/**
	 * ����ʹ�õ����ӳ�
	 */
	protected Map<String,TBConnection> m_conn = new HashMap<String,TBConnection>();
	private Stack<String> m_sourceStack = null;
	protected boolean isStartTransaction = false;
	/**
	 * ��������Ƿ�ֻ�ܻع�
	 */
	protected boolean m_onlyRollback = false;
	protected Exception setRollbackOnlyAddr;

	/**
	 * ��ʼ����ĵ�ַ
	 */
	protected Exception m_addr;

	/**
	 * ��ʼ����ʱ��
	 */
	protected long m_startTime;
	protected long m_startHoldConnectionTime = -1;

	/**
	 * Ĭ�Ϲ��췽�����츣spring���ð���������Ĭ��ֵ
	 * guichen add
	 */
	public TBTransactionImpl() {
	}

	public TBTransactionImpl(int queryTimeOut,int aWarnTime) {
		this.timeOut = queryTimeOut;
		this.warnTime = aWarnTime;
	}

	/**
	 * ��Wrap�������Դ�У����ô˷�����
	 * �������Դ���Ƶ�conn�Ѿ����ڣ���ֱ�ӷ���
	 * ���������Դ�л�ȡһ���µ����ӣ���wrapΪDBConnection�󷵻�
	 * @param sourceName
	 * @param ds
	 * @return
	 * @throws java.sql.SQLException
	 */
	public Connection getConnection(String sourceName,DataSource ds,String aDbType,boolean aIsCommitOnCloseConnection,boolean aIsCheckDBOnCommit)throws java.sql.SQLException{
		if(sourceName == null){
			throw new SQLException("����Դ���Ʋ���Ϊ null ");
		}
		TBConnection result = this.m_conn.get(sourceName);
		if(result == null){
			if (this.isStartTransaction() == false) {
				result = TBConnection.wrap(sourceName,ds.getConnection(), this.timeOut,aDbType,aIsCommitOnCloseConnection,aIsCheckDBOnCommit);
			} else {
				if(m_startHoldConnectionTime <0){
				   m_startHoldConnectionTime = System.currentTimeMillis();
				}
				result = TBConnection.wrap(sourceName,ds.getConnection(), this, this.timeOut,aDbType,aIsCommitOnCloseConnection,aIsCheckDBOnCommit);
				this.m_conn.put(sourceName, result);

				//�����ӽ��������޸Ĳ�����ʱ���ִ��setAutoCommit(false)
			}
		}
		return result;
	}
	public static void debug(){

		log.error("transaction print begin ------------------");
		for(TBTransactionImpl item: m_LeaveTransaction){
			Object[] connections = item.m_conn.values().toArray();
			log.error(item.toString() + ": isStart = " +  item.isStartTransaction +":");

			for(Object conn : connections){
				log.error("\t\t" + conn);
			}
		}
		log.error("transaction print end------------------");

	}
	public int getConnectionCount(){
		return this.m_conn.size();
	}
	/**
	 * Ϊ�˽�Լ���ӵĿ����� ��DBConnection��Close��ʱ���жϣ�����Ǽ������񣬵�û��ִ�������޸ĵĲ�����
	 * ��ֱ�ӹر����ӣ�����������������Ƴ������ӡ���һ������ִֻ�в�ѯ������ʱ�򣬱����˳�ʱ��ռ�����ӡ�
	 * @param sourceName
	 */
	public void removeConnection(String sourceName){
		this.m_conn.remove(sourceName);
	}

	/**
	 * ��������
	 * @throws SQLException
	 */
	public void suspend() throws SQLException {

	}

	public void resume() throws SQLException {

	}
	 public boolean isStartTransaction(){
		  return  this.isStartTransaction;
	  }
	public void begin() throws SQLException {
		if (this.isStartTransaction == true) {
			throw new SQLException("�����Ѿ����ڣ������ظ���������");
		}
		this.isStartTransaction = true;
		this.m_addr = new Exception();
		this.m_startTime = System.currentTimeMillis();
		this.m_startHoldConnectionTime = -1;
		m_LeaveTransaction.add(this);
	}

	public void commit() throws SQLException {
		if (this.isStartTransaction == false) {
			throw new SQLException("�����ύδ��ʼ������");
		}
		if (this.m_onlyRollback == true) {
			if(log.isDebugEnabled()){
		        log.debug("��Ϊ�������ύʱ�����������Ѿ���ֻ�ܻع�������ֱ��ִ��rollback����",setRollbackOnlyAddr);
			}
			this.rollback();//ִ�лع�����
			return;
		}

		try {
			// �����ǰ��Ҫ�ύ�����ݿ�������������1������Ҫ�ֱ�У����ÿ�����ӵĿ�����
			if (this.m_conn.size() > 1) {
				for (TBConnection conn:this.m_conn.values()) {
					// У��ÿ�����ӵĿ����ԣ���������жϣ�������쳣
					conn.judgeConnAvailable();
				}
			}
			for (TBConnection conn:this.m_conn.values()) {
				conn.realCommit();
				conn.realClose();
			}
		} catch (Throwable e) {
			log.fatal(e.getMessage(), e);
			this.m_isCommitError = true;
			for (TBConnection conn:this.m_conn.values()) {
				try {
					conn.realRollback();
					conn.realClose();
				} catch (Throwable et) {
					log.fatal("Rollback ERROR:" + et.getMessage(), et);
				}
			}
			throw new SQLException(e.getMessage());
		} finally {
			if(log.isWarnEnabled() && this.m_startHoldConnectionTime > 0){
				long spendtime = (System.currentTimeMillis() - this.m_startHoldConnectionTime);
				if(spendtime >= warnTime){
				   log.warn("�������ʱ�����:" + spendtime,this.m_addr);
				}
			}
			this.clear();
		}
	}

	public void rollback() throws SQLException {
		if (this.m_isCommitError == true) {
			// �ύʧ�ܺ��׳��쳣���µ��ظ��ع�
			m_isCommitError = false;
			return;
		}
		Throwable ex = null;
		try {
			for (TBConnection conn:this.m_conn.values()) {
				try {
					conn.realRollback();
					conn.realClose();
				} catch (Throwable e) {
					ex = e;
				}
			}
		} finally {
			if(log.isWarnEnabled() && this.m_startHoldConnectionTime > 0){
				long spendtime = (System.currentTimeMillis() - this.m_startHoldConnectionTime);
				if(spendtime >= warnTime){
					if (log.isDebugEnabled()) {
						log.debug("�������ʱ�����:" + spendtime,this.m_addr);
					} else {
						log.warn("�������ʱ�����:" + spendtime);
					}
				}
			}
			this.clear();
		}
		if (ex != null) {
			throw new SQLException(ex.getMessage());
		}
	}

	public boolean isRollbackOnly() {
		return this.m_onlyRollback;
	}
	public void setRollbackOnly() {
		if(this.isStartTransaction() == false){
			log.warn("��û�п�ʼ���񣬲�������ֻ�ܻع�������");
		} else {
			this.m_onlyRollback = true;
			this.setRollbackOnlyAddr = new Exception("����setRollbackOnly�ĵ�ַ");
		}
	}

	public void clear() {
		this.isStartTransaction = false;
		this.m_isCommitError = false;
		m_conn.clear();
		if (m_sourceStack != null) {
			m_sourceStack.clear();
			m_sourceStack = null;
		}
		m_onlyRollback = false;
		m_addr = null;
		m_startTime = 0;
		timeOut = -1;
		m_LeaveTransaction.remove(this);
	}
}